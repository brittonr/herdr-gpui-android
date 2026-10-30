package dev.herdr.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.herdr.android.core.CompanionClient;
import dev.herdr.android.core.ClientException;
import dev.herdr.android.core.HostProfile;
import dev.herdr.android.core.KeyChord;
import dev.herdr.android.core.InputRouting;
import dev.herdr.android.core.Shortcut;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Native phone UI. Remote state is display data, never HTML or executable terminal escapes. */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 18, 22);
    private static final int SURFACE = Color.rgb(30, 34, 42);
    private static final int TEXT = Color.rgb(231, 234, 240);
    private static final int MUTED = Color.rgb(155, 167, 185);
    private static final int ACCENT = Color.rgb(229, 172, 85);
    private final Handler main = new Handler();
    private final List<SavedHost> hosts = new ArrayList<>();
    private HostStore store;
    private LinearLayout root, content, toolbar;
    private TextView status, terminal;
    private EditText composer;
    private Button directKeysButton;
    private SavedHost host;
    private CompanionClient client;
    private ThreadPoolExecutor worker;
    private int generation;
    private boolean visible, reading, storageFailed;
    private final java.util.Set<String> pendingDecisions = new java.util.HashSet<>();
    private String page = "hosts", listPage = "agents", paneId, sessionId, paneTitle;
    private final BackOwner backOwner = new BackOwner(this, this::goBack);
    private boolean transcript, liveKeys;
    private String lastBody = "";
    private AlertDialog activeDialog;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!visible || host == null) return;
            refresh(false);
            main.postDelayed(this, page.equals("pane") ? 1500 : 4000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Same rule as the terminal activity: private content, except in a
        // debuggable build that opts into capture so a screen can be reviewed.
        boolean capturable = (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && "1".equals(android.provider.Settings.Global.getString(
                        getContentResolver(), "herdr_probe"));
        if (!capturable) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = new HostStore(this);
        try { hosts.addAll(store.read()); }
        catch (Exception error) { storageFailed = true; }
        if (state != null) {
            String selected = state.getString("host");
            for (SavedHost candidate : hosts) if (candidate.id.equals(selected)) host = candidate;
            paneId = state.getString("pane");
            sessionId = state.getString("session");
            paneTitle = state.getString("title");
            listPage = state.getString("list", "agents");
            transcript = state.getBoolean("transcript");
            page = host == null ? "hosts" : paneId == null ? listPage : "pane";
        }
        render();
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        reloadHosts();
        render();
        if (host != null) startConnection();
    }

    private void reloadHosts() {
        SavedHost selected = host;
        try {
            List<SavedHost> latest = store.read();
            hosts.clear(); hosts.addAll(latest);
            storageFailed = false;
            host = null;
            if (selected != null) {
                for (SavedHost candidate : hosts) {
                    if (candidate.matchesConnection(selected.id, selected.profile)) { host = candidate; break; }
                }
            }
        } catch (Exception error) {
            storageFailed = true;
            hosts.clear();
            host = null;
        }
        if (host == null) { paneId = sessionId = null; page = "hosts"; }
    }

    @Override protected void onStop() {
        visible = false;
        stopConnection();
        if (activeDialog != null) activeDialog.dismiss();
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (host != null) state.putString("host", host.id);
        state.putString("pane", paneId);
        state.putString("session", sessionId);
        state.putString("title", paneTitle);
        state.putString("list", listPage);
        state.putBoolean("transcript", transcript);
        // Tokens, terminal content and drafts must not enter framework saved state.
    }

    private void stopConnection() {
        generation++;
        reading = false;
        liveKeys = false;
        pendingDecisions.clear();
        main.removeCallbacks(poll);
        if (client != null) {
            final CompanionClient old = client;
            old.cancel();
            Thread cleanup = new Thread(old::close, "herdr-disconnect");
            cleanup.setDaemon(true);
            cleanup.start();
        }
        if (worker != null) worker.shutdownNow();
        client = null;
        worker = null;
        setStatus("Disconnected; unsent input discarded");
    }

    private void startConnection() {
        stopConnection();
        if (!visible || host == null) return;
        client = new CompanionClient(host.profile);
        worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(64));
        setStatus("Connecting to " + host.profile.getName() + "…");
        lastBody = "";
        main.post(poll);
    }

    private void selectHost(SavedHost selected) {
        stopConnection();
        host = selected;
        paneId = sessionId = null;
        page = listPage = "agents";
        lastBody = "";
        render();
        startConnection();
    }

    private void showHosts() {
        stopConnection();
        host = null;
        paneId = sessionId = null;
        page = "hosts";
        render();
    }

    private void render() {
        backOwner.setOwned(!page.equals("hosts"));
        liveKeys = false;
        directKeysButton = null;
        composer = null;
        root = column();
        root.setBackgroundColor(BG);
        // fitSystemWindows replaces padding on the same view, so the inset lives
        // on the children and the text stops sitting on the rounded edge.
        root.setFitsSystemWindows(true);
        root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        toolbar = row();
        toolbar.setPadding(dp(16), dp(8), dp(16), 0);
        root.addView(toolbar);
        TextView title = label(page.equals("hosts") ? "Herdr" : page.equals("pane") ? paneTitle : host.profile.getName(), 22, TEXT);
        title.setMaxLines(2);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(button(page.equals("hosts") ? "+ Host" : "Hosts", () -> { if (page.equals("hosts")) editHost(null); else showHosts(); }));
        status = label("", 12, MUTED);
        status.setPadding(dp(16), 0, dp(16), dp(4));
        root.addView(status);
        content = column();
        content.setPadding(dp(16), dp(4), dp(16), dp(12));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (page.equals("hosts")) renderHosts();
        else if (page.equals("pane")) renderPane();
        else {
            LinearLayout tabs = row();
            tabs.addView(button("Agents", () -> showList("agents")), weighted());
            tabs.addView(button("Terminals", () -> showList("panes")), weighted());
            tabs.addView(button("Inbox", () -> showList("requests")), weighted());
            root.addView(tabs);
            LinearLayout actions = row();
            actions.addView(button("Refresh", () -> refresh(true)), weighted());
            actions.addView(button("New agent", this::newAgent), weighted());
            root.addView(actions);
            setStatus("Connecting…");
        }
    }

    private void renderHosts() {
        try {
            Class<?> remoteScreen = Class.forName("dev.herdr.android.RemoteHostsActivity");
            content.addView(button("Remote hosts · SSH", () -> {
                try { startActivity(new android.content.Intent(this, remoteScreen)); }
                catch (RuntimeException unavailable) { setStatus("Remote hosts could not be opened"); }
            }));
        } catch (ClassNotFoundException javaOnlyBuild) {
            // The companion-only APK deliberately has no native remote transport.
        }
        if (hosts.isEmpty() && !storageFailed) {
            content.addView(label("No hosts yet", 20, TEXT));
            content.addView(label("Add a host to reach a Herdr companion over HTTPS. Tokens stay encrypted on this device.", 14, MUTED));
        } else {
            content.addView(label("Connect to the HTTPS endpoint of a Herdr companion. Tokens stay encrypted on this device.", 14, MUTED));
        }
        LinearLayout list = scroll(content);
        if (storageFailed) {
            list.addView(label("Saved hosts could not be decrypted. They have not been overwritten. App storage must be recovered or reset in Android Settings before saving hosts.", 15, ACCENT));
            toolbar.getChildAt(1).setEnabled(false);
        }
        for (SavedHost saved : hosts) {
            LinearLayout card = card(list);
            card.addView(label(saved.profile.getName(), 20, TEXT));
            card.addView(label(saved.profile.getBaseUrl(), 13, MUTED));
            LinearLayout controls = row();
            controls.addView(button("Connect", () -> selectHost(saved)), weighted());
            controls.addView(button("Edit", () -> editHost(saved)), weighted());
            card.addView(controls);
        }
        setStatus("Native Android preview · HTTPS only");
    }

    private void showList(String target) {
        page = listPage = target;
        paneId = sessionId = null;
        liveKeys = false;
        lastBody = "";
        render();
        refresh(true);
    }

    private void openPane(String id, String session, String title) {
        paneId = id;
        sessionId = session;
        paneTitle = title;
        page = "pane";
        transcript = false;
        liveKeys = false;
        lastBody = "";
        render();
        refresh(true);
    }

    private void renderPane() {
        LinearLayout mode = row();
        mode.addView(button("‹ Back", () -> showList(listPage)), weighted());
        mode.addView(button("Screen", () -> changeMode(false)), weighted());
        Button chat = button("Transcript", () -> changeMode(true));
        chat.setEnabled(sessionId != null && !sessionId.isEmpty());
        mode.addView(chat, weighted());
        content.addView(mode);
        content.addView(label(transcript ? "Claude transcript from companion hooks" : "Live screen snapshots · host terminal dimensions", 12, MUTED));
        ScrollView vertical = new ScrollView(this);
        terminal = label("Loading…", transcript ? 15 : 13, TEXT);
        terminal.setTypeface(Typeface.MONOSPACE);
        terminal.setTextIsSelectable(true);
        terminal.setPadding(dp(8), dp(10), dp(8), dp(10));
        terminal.setSaveEnabled(false);
        if (transcript) vertical.addView(terminal);
        else {
            HorizontalScrollView horizontal = new HorizontalScrollView(this);
            horizontal.addView(terminal, new HorizontalScrollView.LayoutParams(-2, -2));
            vertical.addView(horizontal);
        }
        content.addView(vertical, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!transcript) {
            HorizontalScrollView strip = new HorizontalScrollView(this);
            LinearLayout keys = row();
            for (Shortcut shortcut : Shortcut.parseConfiguration(host.shortcuts)) keys.addView(button(shortcut.getLabel(), () -> sendShortcut(shortcut)));
            strip.addView(keys);
            content.addView(strip);
            LinearLayout options = row();
            Button direct = button("Physical keys: off", () -> {});
            directKeysButton = direct;
            direct.setOnClickListener(v -> {
                setLiveKeys(!liveKeys);
                if (liveKeys) { terminal.setFocusableInTouchMode(true); terminal.requestFocus(); }
            });
            options.addView(direct, weighted());
            options.addView(button("Edit keys", this::editShortcuts), weighted());
            if (getPackageName().endsWith(".gpui")) {
                options.addView(button("GPUI", this::openGpuiTerminal), weighted());
            }
            content.addView(options);
        }
        composer = input(transcript ? "Reply to agent…" : "Text to type (Enter is a separate key)", false);
        composer.setSingleLine(false);
        composer.setMinLines(1);
        composer.setMaxLines(4);
        composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        composer.setSaveEnabled(false);
        composer.setOnFocusChangeListener((v, focused) -> { if (focused) setLiveKeys(false); });
        content.addView(composer);
        LinearLayout send = row();
        send.addView(button(transcript ? "Send reply" : "Type text", this::sendComposer), weighted());
        send.addView(button(transcript ? "Interrupt" : "Enter", () -> sendKeys(Collections.singletonList(transcript ? "esc" : "enter"))), weighted());
        content.addView(send);
    }

    private void changeMode(boolean chat) {
        transcript = chat;
        liveKeys = false;
        lastBody = "";
        render();
        refresh(true);
    }

    private interface Operation { String run(CompanionClient connection) throws Exception; }
    private interface Success { void accept(String value) throws Exception; }

    private void request(Operation operation, Success success, boolean write) {
        request(operation, success, write, () -> {});
    }

    private void request(Operation operation, Success success, boolean write, Runnable settled) {
        final CompanionClient connection = client;
        final int epoch = generation;
        if (!visible || connection == null || worker == null) { settled.run(); setStatus("Connect to a host first"); return; }
        try {
            worker.execute(() -> {
                try {
                    String value = operation.run(connection);
                    main.post(() -> {
                        if (generation != epoch || !visible) return;
                        try { success.accept(value); }
                        catch (Exception badReply) { setStatus("The companion returned an unsupported response"); }
                        finally { settled.run(); }
                    });
                } catch (Exception error) {
                    main.post(() -> {
                        if (generation != epoch || !visible) return;
                        settled.run();
                        // ClientException messages are locally authored; raw server and TLS diagnostics stay private.
                        setStatus(error instanceof ClientException ? error.getMessage() : write ? "Send failed. Delivery may be uncertain; input was not retried." : "Connection failed. Check HTTPS, token, and companion availability.");
                    });
                }
            });
        } catch (RejectedExecutionException full) {
            settled.run();
            setStatus("Input queue is full. New input was not sent.");
        }
    }

    private void refresh(boolean force) {
        if (client == null || page.equals("hosts") || reading) return;
        reading = true;
        final String requestedPage = page;
        final String requestedPane = paneId;
        final boolean requestedTranscript = transcript;
        final String requestedSession = sessionId;
        Operation read;
        if (requestedPage.equals("pane")) {
            if (requestedTranscript && requestedSession != null)
                read = c -> c.getTranscript(requestedSession);
            else read = c -> c.getScreen(requestedPane);
        } else read = c -> c.get("v1/" + requestedPage);
        request(read, body -> {
            reading = false;
            if (!page.equals(requestedPage) || !equal(paneId, requestedPane) || transcript != requestedTranscript) return;
            setStatus("Connected · " + host.profile.getName());
            if (!force && body.equals(lastBody)) return;
            lastBody = body;
            JSONObject data = new JSONObject(body);
            if (page.equals("pane")) {
                terminal.setText(transcript ? transcriptText(data.optJSONArray("entries")) : data.getString("text"));
            } else renderItems(data);
        }, false, () -> reading = false);
    }

    private void renderItems(JSONObject response) throws Exception {
        content.removeAllViews();
        LinearLayout list = scroll(content);
        JSONArray values = response.optJSONArray(page);
        String error = response.optString("herdr_error", "");
        if (!error.isEmpty() && !error.equals("null")) list.addView(label("Companion is reachable; its Herdr daemon is unavailable.", 15, ACCENT));
        if (values == null) { list.addView(label("This companion does not support this view. Update the bridge from this Android branch.", 16, ACCENT)); return; }
        if (values.length() == 0) list.addView(label(page.equals("requests") ? "No requests waiting" : "No " + page + " on this host", 18, MUTED));
        for (int i = 0; i < Math.min(values.length(), 200); i++) {
            JSONObject value = values.getJSONObject(i);
            LinearLayout card = card(list);
            if (page.equals("requests")) { requestCard(card, value); continue; }
            String id = value.optString("pane_id", "");
            String title = nonempty(value, "name", "label", "title", "terminal_title", "display_agent", "agent", "pane_id");
            card.addView(label(title, 19, TEXT));
            card.addView(label(nonempty(value, "workspace_label", "workspace_id") + "  ·  " + value.optString("agent_status", "shell"), 13, ACCENT));
            if (!value.isNull("cwd")) card.addView(label(value.optString("cwd"), 13, MUTED));
            String session = value.isNull("session_id") ? null : value.optString("session_id", null);
            if (!id.isEmpty()) card.addView(button("Open terminal", () -> openPane(id, session, title)));
        }
        if (values.length() > 200) list.addView(label("Showing the first 200 items", 13, MUTED));
    }

    private String transcriptText(JSONArray entries) throws Exception {
        if (entries == null || entries.length() == 0) return "No transcript yet. Claude hooks must report this session. Use Screen for other agents.";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(entries.length(), 200); i++) {
            JSONObject item = entries.getJSONObject(i);
            text.append(item.optString("kind").replace('_', ' ').toUpperCase(java.util.Locale.ROOT)).append('\n');
            if (item.has("name")) text.append(item.optString("name")).append(" ");
            text.append(item.optString("text", item.optString("summary", ""))).append("\n\n");
        }
        return text.toString();
    }

    private void sendComposer() {
        if (composer == null || !composer.isEnabled() || composer.length() == 0) return;
        final String target = paneId, text = composer.getText().toString();
        final EditText field = composer;
        final boolean reply = transcript;
        field.setEnabled(false);
        request(c -> requireAck(reply ? c.sendPrompt(target, text) : c.sendText(target, text)), ignored -> {
            if (composer == field && field.getText().toString().equals(text)) field.setText("");
            setStatus(reply ? "Reply accepted by companion" : "Text accepted by companion");
            refresh(true);
        }, true, () -> field.setEnabled(true));
    }

    private void sendKeys(List<String> keys) {
        final String target = paneId;
        if (target == null) return;
        request(c -> requireAck(c.sendKeys(target, keys)), ignored -> { setStatus("Keys accepted by companion"); refresh(true); }, true);
    }

    private void sendShortcut(Shortcut shortcut) {
        final String target = paneId;
        request(c -> requireAck(c.sendShortcut(target, shortcut)), ignored -> { setStatus("Shortcut accepted by companion"); refresh(true); }, true);
    }

    private void setLiveKeys(boolean enabled) {
        liveKeys = enabled;
        if (directKeysButton != null) directKeysButton.setText(enabled ? "Physical keys: ON" : "Physical keys: off");
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        boolean physical = event.isFromSource(InputDevice.SOURCE_KEYBOARD)
            && event.getDevice() != null && !event.getDevice().isVirtual();
        if (!InputRouting.shouldSendToTerminal(liveKeys, page.equals("pane") && !transcript,
                terminal != null && terminal.hasFocus(), composer != null && composer.hasFocus(),
                physical, (event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0,
                activeDialog != null && activeDialog.isShowing())) return super.dispatchKeyEvent(event);
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN || event.isSystem()) return super.dispatchKeyEvent(event);
        if (KeyEvent.isModifierKey(event.getKeyCode())) return true;
        if (event.getAction() == KeyEvent.ACTION_UP) return true;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        if (event.isMetaPressed() || event.isSymPressed() || event.isFunctionPressed()) {
            setStatus("Meta, Sym and Function combinations are not forwarded");
            return true;
        }
        String named = keyName(event.getKeyCode());
        if (event.isCtrlPressed() || event.isAltPressed() || named != null) {
            try {
                String key = named == null ? String.valueOf((char) event.getUnicodeChar(0)) : named;
                sendKeys(Collections.singletonList(KeyChord.fromPhysical(key, event.isCtrlPressed(), event.isAltPressed(), event.isShiftPressed())));
            } catch (IllegalArgumentException unsupported) { setStatus("This key combination is not supported by the companion"); }
            return true;
        }
        int codepoint = event.getUnicodeChar();
        if ((codepoint & android.view.KeyCharacterMap.COMBINING_ACCENT) != 0) { setStatus("Use the text field for composed characters"); return true; }
        if (codepoint != 0) {
            String text = new String(Character.toChars(codepoint));
            final String target = paneId;
            request(c -> requireAck(c.sendText(target, text)), ignored -> {}, true);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private String keyName(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return "enter";
            case KeyEvent.KEYCODE_ESCAPE: return "esc";
            case KeyEvent.KEYCODE_TAB: return "tab";
            case KeyEvent.KEYCODE_DEL: return "backspace";
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            default: if (key >= KeyEvent.KEYCODE_F1 && key <= KeyEvent.KEYCODE_F12) return "f" + (key - KeyEvent.KEYCODE_F1 + 1); return null;
        }
    }

    @Override public void onBackPressed() {
        if (page.equals("hosts")) super.onBackPressed();
        else goBack();
    }

    /** A pane returns to its list and a list returns to the hosts. */
    private void goBack() {
        if (page.equals("pane")) showList(listPage);
        else showHosts();
    }

    private void editHost(SavedHost old) {
        if (storageFailed) return;
        LinearLayout form = column();
        form.setPadding(dp(16), 0, dp(16), 0);
        EditText name = input("Host name", false), url = input("https://host.example.com", false), token = input(old == null ? "Companion token" : "New token (leave blank to retain)", true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (old != null) { name.setText(old.profile.getName()); url.setText(old.profile.getBaseUrl()); }
        form.addView(name); form.addView(url); form.addView(token);
        TextView error = label("HTTPS with a trusted certificate is required. The bearer token grants terminal access.", 13, MUTED);
        form.addView(error);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(old == null ? "Add host" : "Edit host").setView(form)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null)
            .setNeutralButton(old == null ? "" : "Delete", old == null ? null : (d, which) -> deleteHost(old)).create();
        activeDialog = dialog;
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String secret = token.getText().toString();
                if (old != null && secret.isEmpty()) secret = old.profile.getToken();
                List<SavedHost> next = store.saveProfile(old,
                    new HostProfile(name.getText().toString(), url.getText().toString(), secret));
                hosts.clear(); hosts.addAll(next);
                token.setText("");
                dialog.dismiss();
                render();
            } catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); }
            catch (Exception failure) { error.setText("Could not save encrypted host settings. Existing settings were kept."); }
        }));
        dialog.show();
    }

    private void deleteHost(SavedHost saved) {
        activeDialog = new AlertDialog.Builder(this).setTitle("Forget " + saved.profile.getName() + "?")
            .setMessage("Remove its saved address, token, shortcuts and quick commands from this device? Remote terminals will keep running.")
            .setNegativeButton("Cancel", null).setPositiveButton("Forget", (d, which) -> {
                try { List<SavedHost> next = store.deleteHost(saved); hosts.clear(); hosts.addAll(next); render(); }
                catch (Exception failure) { setStatus("Could not remove the saved host"); }
            }).show();
    }

    private void openGpuiTerminal() {
        try {
            Class<?> activity = Class.forName("dev.herdr.android.GpuiLaunch");
            activity.getMethod("launch", Activity.class, SavedHost.class, String.class, String.class)
                .invoke(null, this, host, paneId, paneTitle);
        } catch (ReflectiveOperationException unavailable) {
            setStatus("GPUI terminal is unavailable in this build");
        }
    }

    private void editShortcuts() {
        final SavedHost edited = host;
        LinearLayout form = column();
        form.setPadding(dp(16), 0, dp(16), 0);
        form.addView(label("One button per line. Examples:\nStop = key:ctrl+c\nNext = keys:down,enter\nStatus = text:git status\nText does not send Enter. Use \\n only deliberately.", 13, MUTED));
        EditText keys = input("Shortcuts", false);
        keys.setSingleLine(false); keys.setMinLines(6); keys.setMaxLines(10); keys.setText(host.shortcuts);
        form.addView(keys);
        TextView error = label("Up to 24 buttons, saved per host", 13, MUTED); form.addView(error);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Extra keys").setView(form)
            .setNegativeButton("Cancel", null).setNeutralButton("Defaults", null).setPositiveButton("Save", null).create();
        activeDialog = dialog;
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> keys.setText(SavedHost.DEFAULT_KEYS));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    if (host != edited) { dialog.dismiss(); return; }
                    List<SavedHost> next = store.updateShortcuts(edited, keys.getText().toString());
                    hosts.clear(); hosts.addAll(next);
                    for (SavedHost saved : next) if (saved.id.equals(edited.id)) { host = saved; break; }
                    dialog.dismiss(); render(); lastBody = ""; refresh(true);
                } catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); }
                catch (Exception failure) { error.setText("Could not save shortcuts"); }
            });
        });
        dialog.show();
    }

    private void newAgent() {
        final int epoch = generation;
        LinearLayout form = column(); form.setPadding(dp(16), 0, dp(16), 0);
        EditText kind = input("Kind (claude, codex…)", false), name = input("Unique name, e.g. api-fix", false), cwd = input("Directory on host (optional)", false);
        kind.setText("claude"); form.addView(kind); form.addView(name); form.addView(cwd);
        TextView error = label("Starts an agent in a new tab of the host’s focused workspace.", 13, MUTED); form.addView(error);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Start an agent").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Start", null).create(); activeDialog = dialog;
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (epoch != generation) { dialog.dismiss(); return; }
            if (!name.getText().toString().matches("[a-z][a-z0-9_-]{0,31}")) { error.setText("Name must start with a lowercase letter, then use letters, digits, - or _, up to 32 characters."); return; }
            try {
                JSONObject placement = new JSONObject().put("in", "tab").put("workspace_id", JSONObject.NULL);
                if (cwd.length() > 0) placement.put("cwd", cwd.getText().toString());
                String body = new JSONObject().put("kind", kind.getText().toString()).put("name", name.getText().toString()).put("placement", placement).toString();
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                error.setText("Starting… If this times out, check Agents before trying again.");
                request(c -> c.post("v1/agents", body), result -> {
                    JSONObject started = new JSONObject(result).getJSONObject("agent");
                    if (started.optString("pane_id", "").isEmpty()) throw new IllegalStateException("Missing agent");
                    if (dialog.isShowing()) { dialog.dismiss(); showList("agents"); }
                    else setStatus("Agent created. Open Agents to see it.");
                }, true, () -> {
                    if (dialog.isShowing()) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        error.setText("Could not confirm startup. Check Agents before retrying; it may already have started.");
                    }
                });
            } catch (Exception invalid) { error.setText("Invalid agent settings"); }
        })); dialog.show();
    }

    private void requestCard(LinearLayout card, JSONObject value) throws Exception {
        String tool = value.optString("tool_name", "Request"), id = String.valueOf(value.getLong("id"));
        card.addView(label(tool, 19, TEXT));
        card.addView(label(value.optString("cwd", ""), 12, MUTED));
        String description = value.optJSONObject("tool_input") == null ? value.optString("tool_input") : value.getJSONObject("tool_input").toString(2);
        boolean truncated = description.length() > 12000;
        TextView details = label(truncated ? description.substring(0, 12000) + "\n… truncated; use terminal" : description, 13, TEXT);
        details.setTypeface(Typeface.MONOSPACE); details.setTextIsSelectable(true); card.addView(details);
        LinearLayout buttons = row();
        // Question cards are explicitly handed to the terminal; do not approve unanswered questions.
        if (!truncated && !tool.equals("AskUserQuestion")) buttons.addView(button("Review allow", () -> confirmDecision(id, tool)), weighted());
        buttons.addView(button("Deny", () -> denyDecision(id)), weighted());
        buttons.addView(button("Use terminal", () -> decide(id, "{\"behavior\":\"terminal\"}")), weighted());
        card.addView(buttons);
    }

    private void confirmDecision(String id, String tool) {
        final int epoch = generation;
        activeDialog = new AlertDialog.Builder(this).setTitle("Allow " + tool + "?")
            .setMessage("Approve exactly the tool input shown on the request card. This may run commands or change files on the remote host.")
            .setNegativeButton("Cancel", null).setPositiveButton("Allow once", (d, which) -> { if (epoch == generation) decide(id, "{\"behavior\":\"allow\"}"); }).show();
    }

    private void denyDecision(String id) {
        final int epoch = generation;
        EditText reason = input("Reason for the agent", false);
        activeDialog = new AlertDialog.Builder(this).setTitle("Deny request").setView(reason).setNegativeButton("Cancel", null)
            .setPositiveButton("Deny", (d, which) -> {
                if (epoch != generation) return;
                try { decide(id, new JSONObject().put("behavior", "deny").put("message", reason.getText().toString()).toString()); }
                catch (Exception invalid) { setStatus("Invalid response"); }
            }).show();
    }

    private void decide(String id, String body) {
        if (!pendingDecisions.add(id)) return;
        request(c -> requireAck(c.post("v1/requests/" + id, body)), ignored -> { lastBody = ""; refresh(true); }, true, () -> pendingDecisions.remove(id));
    }

    private static String requireAck(String body) throws Exception {
        if (!Boolean.TRUE.equals(new JSONObject(body).opt("ok"))) throw new IllegalStateException("Missing acknowledgment");
        return body;
    }

    private String nonempty(JSONObject object, String... fields) {
        for (String field : fields) { String value = object.optString(field, ""); if (!value.isEmpty() && !value.equals("null")) return value; }
        return "Terminal";
    }
    private static boolean equal(Object a, Object b) { return a == null ? b == null : a.equals(b); }
    private void setStatus(String text) { if (status != null) status.setText(text); }
    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private TextView label(String text, int size, int color) { TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color); v.setPadding(dp(4), dp(6), dp(4), dp(6)); v.setSaveEnabled(false); return v; }
    private Button button(String text, Runnable click) {
        Button v = new Button(this);
        v.setText(text);
        v.setTextSize(13);
        v.setAllCaps(false);
        v.setMinWidth(0);
        v.setMinimumWidth(0);
        // The platform tint reads as a different product beside the terminal chrome.
        v.setTextColor(TEXT);
        v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(SURFACE));
        v.setMinHeight(dp(48));
        v.setStateListAnimator(null);
        v.setOnClickListener(w -> click.run());
        return v;
    }
    private EditText input(String hint, boolean password) { EditText v = new EditText(this); v.setHint(hint); v.setSingleLine(true); v.setTextColor(TEXT); v.setHintTextColor(MUTED); v.setSaveEnabled(false); v.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); v.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)); return v; }
    private LinearLayout scroll(LinearLayout parent) { ScrollView scroll = new ScrollView(this); LinearLayout list = column(); scroll.addView(list); parent.addView(scroll, new LinearLayout.LayoutParams(-1, -1)); return list; }
    private LinearLayout card(LinearLayout parent) { LinearLayout card = column(); card.setPadding(dp(10), dp(6), dp(10), dp(10)); card.setBackgroundColor(SURFACE); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(8), 0, dp(4)); parent.addView(card, params); return card; }
}
