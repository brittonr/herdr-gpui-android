package dev.herdr.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** SSH login and pane selection. Passwords never enter a profile, Intent or saved state. */
public final class RemoteHostsActivity extends Activity {
    private static final int BG = Color.rgb(16, 18, 22), TEXT = Color.rgb(231, 234, 240);
    private static final int MUTED = Color.rgb(155, 167, 185);
    private final Handler main = new Handler();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(8));
    private volatile int generation;
    private volatile boolean visible;
    private RemoteHostStore store;
    private LinearLayout content;
    private TextView status;
    private AlertDialog dialog;
    private EditText password;
    private EditText keyPassphrase;
    private char[] pendingSecret;
    private char[] pendingPassphrase;
    private SavedRemoteHost selected;
    private volatile long ownedHandle;
    private boolean openingPane;
    private String paneSnapshot = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = new RemoteHostStore(this);
        shell();
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        showProfiles();
    }

    @Override protected void onPause() {
        visible = false;
        cancelConnection();
        dismissDialog();
        content.removeAllViews();
        super.onPause();
    }

    @Override protected void onDestroy() {
        cancelConnection();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        // Deliberately omit view state, passwords, edit drafts and remote content.
    }

    private void shell() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setFitsSystemWindows(true);
        privateView(root);
        root.addView(label("Remote Herdr", 24, TEXT));
        status = label("", 14, MUTED);
        root.addView(status);
        ScrollView scroll = new ScrollView(this);
        content = column();
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void cancelConnection() {
        generation++;
        // Let fenced callbacks run: a late connect callback must close its unclaimed handle.
        worker.getQueue().clear();
        clearSecret();
        long old = ownedHandle;
        ownedHandle = 0;
        selected = null;
        paneSnapshot = "";
        openingPane = false;
        if (old != 0) RemoteNative.close(old);
    }

    private synchronized void clearSecret() {
        if (pendingSecret != null) Arrays.fill(pendingSecret, '\0');
        pendingSecret = null;
        if (pendingPassphrase != null) Arrays.fill(pendingPassphrase, '\0');
        pendingPassphrase = null;
        if (password != null) password.setText("");
        if (keyPassphrase != null) keyPassphrase.setText("");
    }

    private void dismissDialog() {
        clearSecret();
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        password = null;
        keyPassphrase = null;
    }

    private void showProfiles() {
        cancelConnection();
        dismissDialog();
        content.removeAllViews();
        setStatus("Loading encrypted profiles…");
        int token = generation;
        run(token, () -> store.read(), hosts -> renderProfiles(hosts));
    }

    private void renderProfiles(List<SavedRemoteHost> hosts) {
        content.removeAllViews();
        setStatus("SSH · credentials are used once and never saved");
        content.addView(label("Connect to an existing Herdr daemon through SSH. Verify its host key before entering credentials.", 15, MUTED));
        content.addView(button("Add remote host", () -> editProfile(null)));
        content.addView(button("Companion hosts", () -> startActivity(new android.content.Intent(this, MainActivity.class))));
        if (hosts.isEmpty()) content.addView(label("No remote profiles saved yet", 18, TEXT));
        for (SavedRemoteHost host : hosts) {
            LinearLayout card = column();
            card.setPadding(0, dp(18), 0, dp(10));
            card.addView(label(host.name, 21, TEXT));
            card.addView(label("tcp".equals(host.transport)
                    ? host.username + "@" + host.host + ":" + host.port + " · " + host.session
                    : "Iroh · " + host.endpointId + " · " + host.session, 14, MUTED));
            card.addView(label(host.expectedFingerprint.isEmpty() ? "Host key not trusted yet" : host.expectedFingerprint, 12, MUTED));
            card.addView(button("Connect", () -> inspectHost(host)));
            card.addView(button("Edit profile", () -> editProfile(host)));
            card.addView(button("Edit extra keys", () -> editShortcuts(host)));
            if (!host.expectedFingerprint.isEmpty()) card.addView(button("Forget SSH trust", () -> forgetTrust(host)));
            card.addView(button("Delete profile", () -> deleteProfile(host)));
            content.addView(card);
        }
    }

    private void editProfile(SavedRemoteHost old) {
        generation++;
        dismissDialog();
        LinearLayout fields = column();
        fields.setPadding(dp(16), 0, dp(16), 0);
        EditText name = field(fields, "Profile name", old == null ? "" : old.name, false);
        Spinner transport = new Spinner(this);
        transport.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Direct TCP + SSH", "Iroh + SSH"}));
        privateView(transport);
        transport.setSelection(old != null && "iroh".equals(old.transport) ? 1 : 0);
        fields.addView(transport);
        EditText host = field(fields, "SSH hostname or IP", old == null ? "" : old.host, false);
        EditText port = field(fields, "SSH port", old == null ? "22" : Integer.toString(old.port), false);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText endpoint = field(fields, "Iroh endpoint ID (Iroh only)", old == null ? "" : old.endpointId, false);
        fields.addView(label("Iroh requires an Iroh SSH receiver on the host. Its receiver selects the SSH port; the port above applies only to direct TCP.", 12, MUTED));
        EditText user = field(fields, "SSH username", old == null ? "" : old.username, false);
        EditText session = field(fields, "Herdr session", old == null ? "default" : old.session, false);
        TextView error = label("Changing the connection address, user or session clears saved SSH trust.", 13, MUTED);
        fields.addView(error);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);
        AlertDialog editing = new AlertDialog.Builder(this).setTitle(old == null ? "Add remote host" : "Edit remote host")
                .setView(scroll).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        showDialog(editing);
        editing.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                String kind = transport.getSelectedItemPosition() == 0 ? "tcp" : "iroh";
                SavedRemoteHost draft = SavedRemoteHost.create(value(name), kind, value(host),
                        "tcp".equals(kind) ? Integer.parseInt(value(port)) : 22,
                        value(endpoint), value(user), value(session));
                editing.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                int token = generation;
                run(token, () -> store.saveProfile(old, draft), saved -> {
                    dismissDialog();
                    showProfiles();
                }, failure -> {
                    editing.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    error.setText(failure);
                });
            } catch (Exception failure) { error.setText("Check all profile fields and enter an SSH port from 1 to 65535."); }
        });
    }

    private void forgetTrust(SavedRemoteHost host) {
        generation++;
        showDialog(new AlertDialog.Builder(this).setTitle("Forget SSH trust?")
                .setMessage("Remove the saved fingerprint for " + host.name + "? Verify its next fingerprint independently before trusting it again.")
                .setNegativeButton("Cancel", null).setPositiveButton("Forget trust", (which, index) -> {
                    int token = generation;
                    run(token, () -> store.forgetTrust(host), ignored -> showProfiles());
                }).create());
    }

    private void editShortcuts(SavedRemoteHost host) {
        generation++;
        dismissDialog();
        LinearLayout fields = column();
        fields.setPadding(dp(16), 0, dp(16), 0);
        fields.addView(label("One button per line, up to 24. Text inserts without Enter.\nExamples:\nStop = key:ctrl+c\nStatus = text:git status\nNext = keys:down,enter", 14, MUTED));
        EditText keys = field(fields, "Extra keys", host.shortcuts, false);
        keys.setSingleLine(false);
        keys.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        keys.setMinLines(5);
        TextView error = label("", 13, MUTED);
        fields.addView(error);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);
        AlertDialog editor = new AlertDialog.Builder(this).setTitle("Extra keys · " + host.name)
                .setView(scroll).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        showDialog(editor);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String configuration = value(keys);
            try { dev.herdr.android.core.Shortcut.parseConfiguration(configuration); }
            catch (IllegalArgumentException invalid) { error.setText(safeMessage(invalid.getMessage())); return; }
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            run(generation, () -> store.updateShortcuts(host, configuration), saved -> {
                dismissDialog(); showProfiles();
            }, failure -> {
                error.setText(failure);
                editor.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            });
        });
    }

    private void deleteProfile(SavedRemoteHost host) {
        generation++;
        showDialog(new AlertDialog.Builder(this).setTitle("Delete remote profile?")
                .setMessage("Remove " + host.name + " and its saved SSH trust and quick commands from this device?")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete", (which, index) -> {
                    int token = generation;
                    run(token, () -> { store.delete(host); return true; }, ignored -> showProfiles());
                }).create());
    }

    private void inspectHost(SavedRemoteHost host) {
        cancelConnection();
        selected = host;
        content.removeAllViews();
        content.addView(button("Cancel and return to profiles", this::showProfiles));
        setStatus("Checking the SSH host key for " + host.name + "…");
        int token = generation;
        run(token, () -> {
            SavedRemoteHost current = store.requireCurrent(host);
            RemoteNative.ensureLoaded(this);
            return result(RemoteNative.inspect(current.connectionJson()));
        }, inspection -> {
            try {
                String key = SavedRemoteHost.fingerprint(inspection.getString("fingerprint"));
                if (key.isEmpty()) throw new IllegalArgumentException("Missing SSH host key");
                if (!host.expectedFingerprint.isEmpty()) {
                    if (!host.expectedFingerprint.equals(key)) {
                        setStatus("SSH HOST KEY CHANGED. Connection blocked. Verify the change independently, then use Forget SSH trust from the profile list.");
                        return;
                    }
                    showPassword(host);
                    return;
                }
                setStatus("Verify this SSH fingerprint before trusting the host");
                showDialog(new AlertDialog.Builder(this).setTitle("Trust this SSH host key?")
                        .setMessage(destination(host) + "\n\n" + key
                                + "\n\nCompare this SHA-256 fingerprint with a trusted source. No password has been sent.")
                        .setNegativeButton("Cancel", (which, index) -> showProfiles())
                        .setPositiveButton("Trust fingerprint", (which, index) -> run(token,
                                () -> store.trust(host, key), trusted -> { selected = trusted; showPassword(trusted); }))
                        .create());
            } catch (Exception failure) { setStatus("SSH host-key inspection returned an invalid response."); }
        });
    }

    private void showPassword(SavedRemoteHost host) { showCredentials(host, false); }

    private void showCredentials(SavedRemoteHost host, boolean privateKey) {
        dismissDialog();
        LinearLayout fields = column();
        fields.setPadding(dp(16), 0, dp(16), 0);
        fields.addView(label(destination(host), 16, TEXT));
        fields.addView(label("Trusted " + host.expectedFingerprint
                + "\nCredentials are used once and cleared on close or pause.", 13, MUTED));
        fields.addView(label("The app checks for a running Herdr session. Herdr’s bridge may start it if it stops while connecting.", 13, MUTED));
        password = field(fields, privateKey ? "Existing OpenSSH / PEM private key" : "SSH password", "", true);
        if (privateKey) {
            password.setSingleLine(false);
            password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            password.setMinLines(3); password.setMaxLines(6);
            keyPassphrase = field(fields, "Key passphrase (optional)", "", true);
            fields.addView(label("Paste an existing key. This app does not create or save SSH keys.", 13, MUTED));
        }
        ScrollView scrolling = new ScrollView(this); scrolling.addView(fields);
        AlertDialog login = new AlertDialog.Builder(this).setTitle("Connect to " + host.name).setView(scrolling)
                .setNegativeButton("Cancel", (which, index) -> showProfiles())
                .setNeutralButton(privateKey ? "Use password" : "Use private key", (which, index) -> showCredentials(host, !privateKey))
                .setPositiveButton("Connect", null).create();
        showDialog(login);
        final int loginGeneration = generation;
        login.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!current(loginGeneration) || dialog != login || !login.isShowing()) return;
            if (password == null || password.length() == 0) return;
            int maximum = privateKey ? 65536 : 4096;
            if (!boundedSecret(password.getText(), maximum)) {
                password.setError(privateKey ? "Key exceeds 64 KiB" : "Password exceeds 4096 bytes"); return;
            }
            if (keyPassphrase != null && !boundedSecret(keyPassphrase.getText(), 4096)) {
                keyPassphrase.setError("Passphrase exceeds 4096 bytes"); return;
            }
            final char[] secret = value(password).toCharArray();
            final char[] phrase = keyPassphrase == null ? new char[0] : value(keyPassphrase).toCharArray();
            synchronized (this) { pendingSecret = secret; pendingPassphrase = phrase; }
            password.setText("");
            if (keyPassphrase != null) keyPassphrase.setText("");
            login.dismiss(); dialog = null; password = null; keyPassphrase = null;
            connect(host, secret, phrase, privateKey);
        });
        login.setOnCancelListener(ignored -> showProfiles());
    }

    private void connect(SavedRemoteHost host, char[] secret, char[] phrase, boolean privateKey) {
        setStatus("Connecting to " + host.name + "…");
        int token = generation;
        submit(() -> {
            long handle = 0;
            try {
                if (!current(token)) return;
                SavedRemoteHost currentHost = store.requireCurrent(host);
                String credential;
                synchronized (this) {
                    if (!current(token) || pendingSecret != secret) return;
                    JSONObject payload = new JSONObject().put(privateKey ? "private_key" : "password", new String(secret));
                    if (privateKey && phrase.length > 0) payload.put("passphrase", new String(phrase));
                    credential = payload.toString();
                    Arrays.fill(secret, '\0'); Arrays.fill(phrase, '\0');
                    pendingSecret = null; pendingPassphrase = null;
                }
                handle = RemoteNative.connect(currentHost.connectionJson(), credential);
                if (handle <= 0) throw new IllegalStateException("Remote connection could not be started");
                final long readyHandle = handle;
                main.post(() -> {
                    if (!current(token)) { RemoteNative.close(readyHandle); return; }
                    ownedHandle = readyHandle;
                    selected = currentHost;
                    pollConnection(token, readyHandle, currentHost);
                });
                handle = 0;
            } catch (Exception | LinkageError failure) {
                main.post(() -> { if (current(token)) setStatus("SSH connection failed. Return to profiles to try again."); });
            } finally {
                if (handle != 0) RemoteNative.close(handle);
                synchronized (this) {
                    Arrays.fill(secret, '\0'); Arrays.fill(phrase, '\0');
                    if (pendingSecret == secret) pendingSecret = null;
                    if (pendingPassphrase == phrase) pendingPassphrase = null;
                }
            }
        });
    }

    private void pollConnection(int token, long handle, SavedRemoteHost host) {
        if (!current(token) || ownedHandle != handle) return;
        run(token, () -> {
            store.requireCurrent(host);
            return result(RemoteNative.poll(handle));
        }, snapshot -> {
            if (ownedHandle != handle) return;
            setStatus(snapshot.optString("status", "Connecting…"));
            if ("closed".equals(snapshot.optString("state"))) {
                String reason = snapshot.optString("status", "SSH connection closed");
                cancelConnection();
                setStatus(safeMessage(reason) + " Return to profiles to reconnect.");
                return;
            }
            if ("ready".equals(snapshot.optString("state"))) {
                JSONArray panes = snapshot.optJSONArray("panes");
                if (panes != null && !panes.toString().equals(paneSnapshot)) {
                    paneSnapshot = panes.toString();
                    renderPanes(host, handle, panes);
                }
            }
            main.postDelayed(() -> pollConnection(token, handle, host), 700);
        }, failure -> {
            cancelConnection();
            setStatus(failure + " Return to profiles to reconnect.");
        });
    }

    private void renderPanes(SavedRemoteHost host, long handle, JSONArray panes) {
        content.removeAllViews();
        content.addView(button("Disconnect and return to profiles", this::showProfiles));
        content.addView(label("Choose a terminal · " + host.name, 20, TEXT));
        if (panes.length() == 0) content.addView(label("No terminal panes in this Herdr session", 16, MUTED));
        for (int i = 0; i < Math.min(panes.length(), 256); i++) {
            JSONObject pane = panes.optJSONObject(i);
            if (pane == null) continue;
            String id = pane.optString("id", "");
            if (!id.matches("[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)*") || id.length() > 128) continue;
            String title = GpuiTerminalProtocol.title(pane.optString("title", id));
            content.addView(button(title, () -> openPane(host, handle, id, title)));
        }
    }

    private void openPane(SavedRemoteHost host, long handle, String id, String title) {
        if (ownedHandle != handle || openingPane) return;
        openingPane = true;
        setStatus("Requesting selected pane…");
        int token = generation;
        run(token, () -> {
            SavedRemoteHost currentHost = store.requireCurrent(host);
            if (!current(token) || ownedHandle != handle) throw new IllegalStateException("Remote selection was cancelled");
            JSONObject response = result(RemoteNative.focus(handle, id));
            if (!Boolean.TRUE.equals(response.opt("queued"))) throw new IllegalStateException("Pane focus was not queued");
            return currentHost;
        }, currentHost -> {
            if (ownedHandle != handle) return;
            try {
                GpuiLaunch.launchRemote(this, currentHost, handle, id, title);
                // startActivity succeeded: only the launch grant/terminal now owns the handle.
                ownedHandle = 0;
                generation++;
                setStatus("Terminal opened");
            } catch (Exception | LinkageError failure) {
                openingPane = false;
                setStatus("The GPUI terminal could not be opened. You can retry or disconnect.");
            }
        }, failure -> { openingPane = false; setStatus(failure); });
    }

    private interface Work<T> { T execute() throws Exception; }
    private interface Done<T> { void apply(T result); }
    private interface Failed { void apply(String message); }

    private <T> void run(int token, Work<T> action, Done<T> done) {
        run(token, action, done, message -> setStatus(message));
    }

    private <T> void run(int token, Work<T> action, Done<T> done, Failed failed) {
        submit(() -> {
            if (!current(token)) return;
            try {
                T result = action.execute();
                main.post(() -> { if (current(token)) done.apply(result); });
            } catch (Exception | LinkageError failure) {
                String message = failure instanceof IllegalStateException || failure instanceof IllegalArgumentException
                        ? safeMessage(failure.getMessage()) : "Remote operation failed; check the profile or encrypted storage.";
                main.post(() -> { if (current(token)) failed.apply(message); });
            }
        });
    }

    private void submit(Runnable task) {
        try { worker.execute(task); }
        catch (RejectedExecutionException busy) { clearSecret(); setStatus("An operation is still finishing. Please try again."); }
    }

    private boolean current(int token) { return visible && generation == token; }
    private static JSONObject result(String body) throws Exception {
        JSONObject result = new JSONObject(body);
        if (result.has("error") && !result.isNull("error") && !result.optString("error").isEmpty())
            throw new IllegalStateException(safeMessage(result.optString("error")));
        return result;
    }
    private static String safeMessage(String value) {
        if (value == null || value.isEmpty()) return "Remote operation failed";
        return value.replaceAll("[\\p{Cntrl}]", " ").substring(0, Math.min(value.length(), 500));
    }
    private void showDialog(AlertDialog value) {
        if (dialog != null) dialog.dismiss();
        if (value.getWindow() == null) throw new IllegalStateException("Secure dialog window is unavailable");
        // Set before attaching the window; asynchronous readers/rendering can
        // observe isShowing immediately during show().
        value.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog = value;
        value.show();
    }
    private void setStatus(String value) { status.setText(value); }
    private static String destination(SavedRemoteHost host) {
        return "iroh".equals(host.transport)
                ? host.username + " via Iroh\n" + host.endpointId
                : host.username + "@" + host.host + ":" + host.port;
    }
    private static boolean boundedSecret(CharSequence text, int limit) {
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
                bytes += 4;
            } else if (Character.isLowSurrogate(ch)) return false;
            else bytes += ch < 0x80 ? 1 : ch < 0x800 ? 2 : 3;
            if (bytes > limit) return false;
        }
        return true;
    }
    private static String value(EditText field) { return field.getText().toString(); }
    private void privateView(View view) {
        view.setSaveEnabled(false);
        view.setSaveFromParentEnabled(false);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    }
    private EditText field(LinearLayout parent, String hint, String value, boolean secret) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setTextColor(TEXT);
        field.setHintTextColor(MUTED);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        privateView(field);
        parent.addView(field);
        return field;
    }
    private TextView label(String text, int size, int color) {
        TextView label = new TextView(this);
        label.setText(text); label.setTextSize(size); label.setTextColor(color);
        label.setPadding(0, dp(6), 0, dp(6)); privateView(label);
        return label;
    }
    private Button button(String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text); button.setAllCaps(false); privateView(button);
        button.setOnClickListener(view -> action.run());
        return button;
    }
    private LinearLayout column() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL); privateView(result);
        return result;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
