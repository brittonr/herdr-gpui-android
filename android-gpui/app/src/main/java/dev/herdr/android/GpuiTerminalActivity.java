package dev.herdr.android;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.inputmethod.BaseInputConnection;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.Toast;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import dev.gpui.mobile.GpuiInputActivity;
import dev.herdr.android.core.ClientException;
import dev.herdr.android.core.CompanionClient;
import dev.herdr.android.core.KeyChord;
import dev.herdr.android.core.Shortcut;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** GPUI paints and routes input; each connection owns either HTTPS or embedded SSH transport. */
public final class GpuiTerminalActivity extends GpuiInputActivity implements SurfaceHolder.Callback {
    private static final AtomicLong GENERATIONS = new AtomicLong();
    private static final long COMMAND_POLL_MS = 50;
    private static final long SCREEN_POLL_MS = 1000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object stateLock = new Object();
    private final Set<Integer> capturedKeys = new HashSet<>();
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "herdr-gpui-https");
        thread.setDaemon(true);
        return thread;
    });
    private GpuiLaunch.Session launch;
    private Connection connection;
    private String shortcutJson;
    private List<Shortcut> shortcuts = Collections.emptyList();
    private boolean hostStarted;
    private boolean destroyed;
    private long imeSession;
    private boolean imeComposing;
    private QuickCommandPalette commandPalette;
    private boolean initialImeHidden;
    private boolean keyboardRequested;
    private boolean allowNextKeyboardShow;
    private GpuiDisplayOptions displayOptions;
    private boolean compactDisplay;
    private FrameLayout terminalRoot;
    private LinearLayout sidebar, sidebarRows;
    private ScrollView sidebarScroll;
    private Button sidebarButton;
    private volatile boolean sidebarOpen;
    private String sidebarSignature = "";
    private AlertDialog displayDialog;
    private Button controlButton;
    private boolean controlLatched;
    private int oneShotKey = KeyEvent.KEYCODE_UNKNOWN;
    /** Index into the renderer's built-in palettes. */
    private int themeIndex;
    private long lastSnapshotGeneration = Long.MIN_VALUE;
    private String lastSnapshotTitle, lastSnapshotText, lastSnapshotStatus, lastSnapshotShortcuts, lastSnapshotSurface;

    @Override protected void onCreate(Bundle state) {
        // A restored activity cannot recover authority from Intent extras or framework saved state.
        launch = state == null ? GpuiLaunch.consume(System.nanoTime()) : null;
        super.onCreate(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().getDecorView().setSaveFromParentEnabled(false);
        getWindow().getDecorView().setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        if (launch == null) {
            Toast.makeText(this, "Reopen the terminal from the host list", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        try {
            shortcuts = Shortcut.parseConfiguration(launch.shortcuts());
            QuickCommandPalette.Owner paletteOwner = new QuickCommandPalette.Owner() {
                public void show(AlertDialog dialog) { showLocalDialog(dialog); }
                public boolean isCurrentDialog(AlertDialog dialog) {
                    return !destroyed && !isFinishing() && displayDialog == dialog && dialog.isShowing();
                }
            };
            commandPalette = launch.remoteHost == null
                    ? new QuickCommandPalette(this, launch.host, launch.title, paletteOwner)
                    : new QuickCommandPalette(this, launch.remoteHost, launch.title, paletteOwner);
            // Remote-mutating controls live on the Java UI thread, never a deferred GPUI click.
            shortcutJson = "[]";
            loadDisplayOptions();
            themeIndex = getSharedPreferences("terminal-display", MODE_PRIVATE).getInt("theme", 0);
            if (themeIndex < 0 || themeIndex > 3) themeIndex = 0;
            nativeStart(this);
            nativeSetTheme(themeIndex);
            hostStarted = true;
            createSurface();
        } catch (IllegalArgumentException invalid) {
            Toast.makeText(this, "Check this host's shortcut settings", Toast.LENGTH_LONG).show();
            finish();
        } catch (LinkageError unavailable) {
            Toast.makeText(this, "The GPUI renderer could not be started", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void createSurface() {
        getWindow().setStatusBarColor(0xff101216);
        getWindow().setNavigationBarColor(0xff101216);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        FrameLayout root = new FrameLayout(this);
        terminalRoot = root;
        root.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> applyDisplayOptions());
        root.setBackgroundColor(0xff101216);
        root.setSaveFromParentEnabled(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bounds = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bounds.left, bounds.top, bounds.right, bounds.bottom);
            } else {
                // Pre-R edge-to-edge uses adjustResize for IME and stable bars for our padding.
                view.setPadding(insets.getStableInsetLeft(), insets.getStableInsetTop(),
                        insets.getStableInsetRight(), insets.getStableInsetBottom());
            }
            root.post(this::applyDisplayOptions);
            return insets;
        });
        SurfaceView surface = new SurfaceView(this);
        surface.setFocusable(true);
        surface.setFocusableInTouchMode(true);
        surface.setSaveEnabled(false);
        surface.getHolder().addCallback(this);
        surface.setOnTouchListener((view, event) -> forwardTouch(event));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(createShortcutBar(), new LinearLayout.LayoutParams(-1, dp(48)));
        // The sidebar rail mirrors the desktop's list of workspaces and agents.
        sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setBackgroundColor(0xff161a21);
        sidebar.setSaveFromParentEnabled(false);
        sidebarScroll = new ScrollView(this);
        sidebarScroll.setBackgroundColor(0xff161a21);
        sidebarRows = new LinearLayout(this);
        sidebarRows.setOrientation(LinearLayout.VERTICAL);
        sidebarRows.setPadding(dp(10), dp(8), dp(10), dp(8));
        sidebarScroll.addView(sidebarRows);
        sidebar.addView(sidebarScroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.addView(sidebar, new LinearLayout.LayoutParams(0, -1));
        columns.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(columns, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        root.requestApplyInsets();
        surface.requestFocus();
        root.post(this::hideInitialKeyboard);
        root.post(() -> { applySidebarDefault(); refreshSidebar(true); });
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
    }

    private View createShortcutBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xff1e222a);
        Button options = localButton("View");
        options.setContentDescription("Terminal display and keyboard options");
        options.setOnClickListener(view -> showDisplayOptions());
        synchronousTouch(options);
        bar.addView(options, new LinearLayout.LayoutParams(dp(60), dp(48)));
        Button commands = localButton("Cmds");
        commands.setContentDescription("Quick commands for this host");
        commands.setOnClickListener(view -> showQuickCommands());
        synchronousTouch(commands);
        bar.addView(commands, new LinearLayout.LayoutParams(dp(64), dp(48)));
        Button panes = localButton("Panes");
        panes.setContentDescription("Workspaces, tabs and panes for this host");
        panes.setOnClickListener(view -> showPanes());
        synchronousTouch(panes);
        bar.addView(panes, new LinearLayout.LayoutParams(dp(64), dp(48)));
        sidebarButton = localButton("List");
        sidebarButton.setContentDescription("Show or hide the workspace and agent sidebar");
        sidebarButton.setOnClickListener(view -> toggleSidebar());
        synchronousTouch(sidebarButton);
        bar.addView(sidebarButton, new LinearLayout.LayoutParams(dp(56), dp(48)));
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(0xff1e222a);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int gap = Math.round(4 * getResources().getDisplayMetrics().density);
        row.setPadding(0, 0, gap, 0);
        controlButton = localButton("Ctrl");
        controlButton.setContentDescription("Control modifier for the next letter");
        controlButton.setOnClickListener(view -> {
            synchronized (stateLock) {
                if (connection == null || connection.isClosed() || !connection.ready
                        || compositionBlocksInput(connection)) return;
                controlLatched = !controlLatched;
                updateControlButton();
            }
        });
        synchronousTouch(controlButton);
        row.addView(controlButton, new LinearLayout.LayoutParams(dp(60), dp(48)));
        for (Shortcut shortcut : shortcuts) {
            Button button = new Button(this);
            button.setText(shortcut.getLabel());
            button.setTextSize(12);
            button.setTextColor(0xffe7eaf0);
            button.setBackgroundTintList(ColorStateList.valueOf(0xff303744));
            button.setAllCaps(false);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setFocusable(false);
            button.setSaveEnabled(false);
            button.setOnClickListener(view -> sendShortcut(shortcut));
            synchronousTouch(button);
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, dp(48));
            layout.setMargins(gap, 0, gap, 0);
            row.addView(button, layout);
        }
        scroll.addView(row);
        bar.addView(scroll, new LinearLayout.LayoutParams(0, dp(48), 1));
        return bar;
    }

    private void synchronousTouch(Button button) {
        GpuiShortcutTouch tap = new GpuiShortcutTouch();
        button.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP
                    && action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_CANCEL
                    && action != MotionEvent.ACTION_POINTER_DOWN && action != MotionEvent.ACTION_POINTER_UP) return false;
            boolean inside = event.getX() >= 0 && event.getX() < view.getWidth()
                    && event.getY() >= 0 && event.getY() < view.getHeight();
            boolean clicked = tap.event(action, inside, event.getPointerCount() == 1);
            view.setPressed(tap.isPressed());
            // Resolve before the next physical event, rather than Button's deferred PerformClick.
            if (clicked) view.performClick();
            return true;
        });
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private Button localButton(String title) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setTextColor(0xffe7eaf0);
        button.setBackgroundTintList(ColorStateList.valueOf(0xff303744));
        button.setFocusable(false);
        button.setSaveEnabled(false);
        return button;
    }

    private void clearControl() {
        if (!controlLatched) return;
        controlLatched = false;
        updateControlButton();
    }

    private void updateControlButton() {
        if (controlButton == null) return;
        controlButton.setText(controlLatched ? "Ctrl •" : "Ctrl");
        controlButton.setSelected(controlLatched);
        controlButton.setContentDescription(controlLatched
                ? "Control armed for next letter. Tap to cancel" : "Control modifier for the next letter");
    }

    private void loadDisplayOptions() {
        SharedPreferences preferences = getSharedPreferences("terminal-display", MODE_PRIVATE);
        try {
            displayOptions = new GpuiDisplayOptions(preferences.getInt("mode", 0),
                    preferences.getInt("font", 0), preferences.getInt("alt", 0));
        } catch (ClassCastException invalid) {
            displayOptions = new GpuiDisplayOptions(0, 0, 0);
        }
    }

    private void applyDisplayOptions() {
        if (!hostStarted || destroyed || terminalRoot == null || displayOptions == null) return;
        // Configuration dimensions follow the app window through fold/orientation changes,
        // but unlike the padded root they are not reduced by transient IME insets.
        Configuration configuration = getResources().getConfiguration();
        float width = configuration.screenWidthDp;
        float height = configuration.screenHeightDp;
        if (width <= 0 || height <= 0) return;
        compactDisplay = displayOptions.compact(width, height);
        nativeSetDisplayPreferences(displayOptions.effectiveFont(compactDisplay,
                getResources().getConfiguration().fontScale), compactDisplay);
    }

    private void hideInitialKeyboard() {
        if (keyboardRequested || initialImeHidden || terminalRoot == null
                || terminalRoot.getWindowToken() == null || !hasWindowFocus()) return;
        initialImeHidden = true;
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(terminalRoot.getWindowToken(), 0);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.hide(WindowInsets.Type.ime());
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) clearControl();
        else hideInitialKeyboard();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        clearControl();
        if (terminalRoot != null) terminalRoot.post(this::applyDisplayOptions);
    }

    private TextView optionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(14);
        label.setPadding(0, dp(8), 0, dp(4));
        label.setSaveEnabled(false);
        return label;
    }

    private Spinner optionSpinner(String[] choices, int selection) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selection);
        spinner.setMinimumHeight(dp(48));
        spinner.setSaveEnabled(false);
        return spinner;
    }

    private void showDisplayOptions() {
        if (displayDialog != null && displayDialog.isShowing()) return;
        clearControl();
        allowNextKeyboardShow = false;
        synchronized (stateLock) {
            imeComposing = false;
            if (connection != null && connection.ready && !connection.isClosed())
                nativeImeEvent(connection.generation, imeSession, 4, "", 0, 0);
        }
        // Reset also fences existing InputConnection wrappers, including same-session ones.
        super.gpuiHideKeyboard(imeSession);
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(16), 0, dp(16), dp(12));
        options.addView(optionLabel("Layout"));
        Spinner mode = optionSpinner(new String[]{"Automatic (available space)", "Compact / Titan 2 Elite", "Standard"}, displayOptions.mode);
        options.addView(mode);
        options.addView(optionLabel("Text size (respects system text scale)"));
        String[] sizes = {"Automatic", "11", "12", "13", "14", "15", "16", "17", "18"};
        Spinner font = optionSpinner(sizes, displayOptions.fontSize == 0 ? 0 : displayOptions.fontSize - 10);
        options.addView(font);
        options.addView(optionLabel("Theme"));
        Spinner theme = optionSpinner(new String[]{"Default", "Nord", "Mocha", "Latte"}, themeIndex);
        options.addView(theme);
        options.addView(optionLabel("Physical Alt key"));
        Spinner alt = optionSpinner(new String[]{"Default (device symbols)", "Device symbols", "Terminal Alt shortcuts"}, displayOptions.altMode);
        options.addView(alt);
        TextView help = optionLabel("Opening View cancels an unfinished on-screen draft. Compact keeps more terminal rows visible. Alt symbols use Android’s key map; Fn/Sym are not guessed. Use Ctrl here for one letter, or map a physical key to Ctrl in your keyboard settings. Tap Keyboard only when you need the on-screen keyboard.");
        options.addView(help);
        synchronized (stateLock) {
            TextView status = optionLabel((lastSnapshotTitle == null ? "Terminal" : lastSnapshotTitle)
                    + "\n" + (lastSnapshotStatus == null ? "" : lastSnapshotStatus));
            status.setTextIsSelectable(true);
            options.addView(status);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(options);
        displayDialog = new AlertDialog.Builder(this).setTitle("Terminal view")
                .setView(scroll).setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> {
                    displayOptions = new GpuiDisplayOptions(mode.getSelectedItemPosition(),
                            font.getSelectedItemPosition() == 0 ? 0 : font.getSelectedItemPosition() + 10,
                            alt.getSelectedItemPosition());
                    themeIndex = theme.getSelectedItemPosition();
                    getSharedPreferences("terminal-display", MODE_PRIVATE).edit()
                            .putInt("mode", displayOptions.mode).putInt("font", displayOptions.fontSize)
                            .putInt("alt", displayOptions.altMode).putInt("theme", themeIndex).apply();
                    nativeSetTheme(themeIndex);
                    applyDisplayOptions();
                }).create();
        showLocalDialog(displayDialog);
    }

    private void showLocalDialog(AlertDialog dialog) {
        if (destroyed || isFinishing()) return;
        AlertDialog previous = displayDialog;
        if (previous != null && previous != dialog) previous.dismiss();
        displayDialog = dialog;
        dialog.setOnDismissListener(closedDialog -> {
            if (displayDialog == closedDialog) displayDialog = null;
            clearControl();
        });
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
    }

    /** Persisted rail choice, defaulting to visible only where it fits. */
    private void applySidebarDefault() {
        boolean wide = getResources().getConfiguration().screenWidthDp >= 700;
        sidebarOpen = wide;
        applySidebarWidth();
    }

    private void toggleSidebar() {
        sidebarOpen = !sidebarOpen;
        applySidebarWidth();
        if (sidebarOpen) refreshSidebar(true);
    }

    private void applySidebarWidth() {
        if (sidebar == null) return;
        int width = sidebarOpen ? Math.min(dp(240), Math.max(dp(160),
                terminalRoot == null ? dp(200) : terminalRoot.getWidth() / 3)) : 0;
        LinearLayout.LayoutParams layout = (LinearLayout.LayoutParams) sidebar.getLayoutParams();
        if (layout != null && layout.width != width) {
            layout.width = width;
            sidebar.setLayoutParams(layout);
        }
        if (sidebarButton != null) {
            sidebarButton.setText(sidebarOpen ? "Hide" : "List");
            sidebarButton.setSelected(sidebarOpen);
        }
    }

    /** Rebuilds the rail only when the daemon's chrome actually changed. */
    private void refreshSidebar(boolean force) {
        if (!sidebarOpen || sidebarRows == null) return;
        Connection current = null;
        synchronized (stateLock) { current = connection; }
        if (current == null || current.isClosed()) return;
        JSONArray workspaces = current.workspaces, tabs = current.tabs, panes = current.panes;
        String signature = String.valueOf(workspaces) + String.valueOf(tabs)
                + String.valueOf(panes) + '|' + current.paneId;
        if (!force && signature.equals(sidebarSignature)) return;
        sidebarSignature = signature;
        final Connection target = current;
        sidebarRows.removeAllViews();
        if (workspaces == null || panes == null) {
            sidebarRows.addView(chromeLabel("No session chrome yet", 12, 0x8c9bb0, false));
            return;
        }
        for (int w = 0; w < workspaces.length(); w++) {
            JSONObject workspace = workspaces.optJSONObject(w);
            if (workspace == null) continue;
            String workspaceId = workspace.optString("id", "");
            String workspaceLabel = chromeTitle(workspace.optString("label", ""), workspaceId);
            TextView header = chromeLabel(workspaceLabel, 13, 0x9fb0c6, true);
            header.setMinimumHeight(dp(36));
            header.setContentDescription("Focus workspace " + workspaceLabel);
            header.setOnClickListener(view -> focusChrome(target, "workspace", workspaceId));
            sidebarRows.addView(header);
            for (int p = 0; p < panes.length(); p++) {
                JSONObject pane = panes.optJSONObject(p);
                if (pane == null || !workspaceId.equals(pane.optString("workspace_id", ""))) continue;
                String paneId = pane.optString("id", "");
                String title = chromeTitle(pane.optString("title", ""), paneId);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(44));
                row.setSaveFromParentEnabled(false);
                row.addView(chromeDot(pane.optString("status", "unknown")));
                row.addView(chromeLabel(title, 12,
                        paneId.equals(target.paneId) ? 0x76d3b2 : 0xc9d3df, false));
                row.setContentDescription("Focus pane " + title);
                row.setOnClickListener(view -> focusRemotePane(target, paneId));
                sidebarRows.addView(row);
            }
        }
    }

    /** Workspace, tab and agent chrome from the daemon snapshot; a tap focuses that pane in place. */
    private void showPanes() {
        if (destroyed || isFinishing() || displayDialog != null && displayDialog.isShowing()) return;
        final Connection target;
        synchronized (stateLock) { target = connection; }
        if (target == null || target.isClosed()) return;
        clearControl();
        allowNextKeyboardShow = false;
        super.gpuiHideKeyboard(imeSession);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(12);
        list.setPadding(padding, padding, padding, padding);
        JSONArray workspaces = target.workspaces, tabs = target.tabs, panes = target.panes;
        int rows = 0;
        if (workspaces != null && panes != null) {
            for (int w = 0; w < workspaces.length(); w++) {
                JSONObject workspace = workspaces.optJSONObject(w);
                if (workspace == null) continue;
                String workspaceId = workspace.optString("id", "");
                TextView header = chromeLabel(chromeTitle(workspace.optString("label", ""), workspaceId),
                        15, 0x9fb0c6, true);
                header.setMinimumHeight(dp(48));
                header.setContentDescription("Focus workspace " + chromeTitle(workspace.optString("label", ""), workspaceId));
                header.setOnClickListener(view -> {
                    if (displayDialog != null) displayDialog.dismiss();
                    focusChrome(target, "workspace", workspaceId);
                });
                list.addView(header);
                if (tabs != null) for (int t = 0; t < tabs.length(); t++) {
                    JSONObject tab = tabs.optJSONObject(t);
                    if (tab == null || !workspaceId.equals(tab.optString("workspace_id", ""))) continue;
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.addView(chromeDot(tab.optString("status", "unknown")));
                    row.addView(chromeLabel(chromeTitle(tab.optString("label", ""), tab.optString("id", "")),
                            13, tab.optBoolean("focused") ? 0xe7eaf0 : 0x8c9bb0, false));
                    row.setMinimumHeight(dp(40));
                    row.setContentDescription("Focus tab " + chromeTitle(tab.optString("label", ""), tab.optString("id", "")));
                    String tabId = tab.optString("id", "");
                    row.setOnClickListener(view -> {
                        if (displayDialog != null) displayDialog.dismiss();
                        focusChrome(target, "tab", tabId);
                    });
                    list.addView(row);
                }
                for (int p = 0; p < panes.length(); p++) {
                    JSONObject pane = panes.optJSONObject(p);
                    if (pane == null || !workspaceId.equals(pane.optString("workspace_id", ""))) continue;
                    rows++;
                    list.addView(paneRow(target, list, pane));
                }
            }
        }
        if (rows == 0) list.addView(chromeLabel("No panes reported by this session yet", 14, 0x9fb0c6, false));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        AlertDialog sheet = new AlertDialog.Builder(this)
                .setTitle("Panes · " + launch.name())
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();
        showLocalDialog(sheet);
    }

    private View paneRow(Connection target, LinearLayout list, JSONObject pane) {
        String id = pane.optString("id", "");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setMinimumHeight(dp(48));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setSaveEnabled(false);
        row.addView(chromeDot(pane.optString("status", "unknown")));
        row.addView(chromeLabel(chromeTitle(pane.optString("title", ""), id),
                15, id.equals(target.paneId) ? 0x76d3b2 : 0xe7eaf0, false));
        row.setContentDescription("Focus pane " + chromeTitle(pane.optString("title", ""), id));
        row.setOnClickListener(view -> {
            if (displayDialog != null) displayDialog.dismiss();
            focusRemotePane(target, id);
        });
        return row;
    }

    private void focusRemotePane(Connection target, String paneId) {
        if (paneId.isEmpty() || paneId.length() > 128
                || !paneId.matches("[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)*")) return;
        if (target.remoteHandle <= 0) {
            Toast.makeText(this, "Switch panes from Remote hosts for this connection", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            JSONObject result = new JSONObject(RemoteNative.focus(target.remoteHandle, paneId));
            if (!result.optBoolean("queued", false)) throw new IllegalStateException("Pane focus was not queued");
            synchronized (stateLock) {
                if (!current(target)) return;
                target.paneId = paneId;
                publish(target, "Switching pane…");
            }
        } catch (Exception | LinkageError failure) {
            Toast.makeText(this, "Could not switch pane; reopen and check the session", Toast.LENGTH_LONG).show();
        }
    }

    /** Tabs and workspaces use the daemon's own switch requests. */
    private void focusChrome(Connection target, String kind, String id) {
        if (id.isEmpty() || id.length() > 128
                || !id.matches("[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)*")) return;
        if (target.remoteHandle <= 0) {
            Toast.makeText(this, "Switch workspaces from Remote hosts for this connection", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            JSONObject result = new JSONObject("tab".equals(kind)
                    ? RemoteNative.focusTab(target.remoteHandle, id)
                    : RemoteNative.focusWorkspace(target.remoteHandle, id));
            if (!result.optBoolean("queued", false)) throw new IllegalStateException("Switch was not queued");
            synchronized (stateLock) {
                if (current(target)) publish(target, "Switching " + kind + "…");
            }
        } catch (Exception | LinkageError failure) {
            Toast.makeText(this, "Could not switch; reopen and check the session", Toast.LENGTH_LONG).show();
        }
    }

    private static String chromeTitle(String value, String fallback) {
        String title = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (title.isEmpty()) title = fallback;
        return title.length() > 60 ? title.substring(0, 60) + "…" : title;
    }

    private View chromeDot(String status) {
        View dot = new View(this);
        int color = 0x8892a0;
        if ("working".equals(status)) color = 0xf0b778;
        else if ("blocked".equals(status)) color = 0xff6b6b;
        else if ("done".equals(status)) color = 0x6ab0f3;
        else if ("idle".equals(status)) color = 0x76d3b2;
        dot.setBackgroundColor(color);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(dp(8), dp(8));
        layout.setMarginEnd(dp(10));
        dot.setLayoutParams(layout);
        return dot;
    }

    private TextView chromeLabel(String text, int size, int color, boolean header) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setSaveEnabled(false);
        if (header) label.setPadding(0, dp(14), 0, dp(4));
        return label;
    }

    private void showQuickCommands() {
        if (destroyed || isFinishing() || commandPalette == null || displayDialog != null && displayDialog.isShowing()) return;
        final Connection target;
        synchronized (stateLock) {
            target = connection;
            if (target != null && compositionBlocksInput(target)) return;
        }
        clearControl();
        allowNextKeyboardShow = false;
        super.gpuiHideKeyboard(imeSession);
        commandPalette.open(new QuickCommandPalette.Sender() {
            public boolean canSend() {
                synchronized (stateLock) {
                    return !destroyed && !isFinishing() && target != null && connection == target && target.ready && !target.isClosed();
                }
            }
            public boolean send(AlertDialog dialog, String text, boolean submit) {
                synchronized (stateLock) {
                    if (displayDialog != dialog || !dialog.isShowing() || !canSend()) return false;
                    try {
                        if (nativeSendCommand(target.generation, GpuiTerminalProtocol.quickCommand(text, submit))) return true;
                        fail(target, "Command was not queued; reopen and check the terminal before retrying");
                    } catch (JSONException | IllegalArgumentException invalid) {
                        publish(target, "Invalid quick command; edit it before sending");
                    }
                    return false;
                }
            }
        });
    }

    private void sendShortcut(Shortcut shortcut) {
        if (displayDialog != null && displayDialog.isShowing()) return;
        clearControl();
        synchronized (stateLock) {
            Connection target = connection;
            if (target == null || target.isClosed()) return;
            if (!target.ready) {
                publish(target, "Wait for the terminal connection before typing");
                return;
            }
            if (compositionBlocksInput(target)) return;
            try {
                if (!nativeSendCommand(target.generation, GpuiTerminalProtocol.shortcutCommand(shortcut))) {
                    fail(target, "Shortcut was not queued; reopen and check the terminal before retrying");
                }
            } catch (JSONException | IllegalArgumentException invalid) {
                fail(target, "Invalid shortcut; check the host's shortcut settings");
            }
        }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (hostStarted) nativeSurfaceCreated(holder.getSurface(), getResources().getDisplayMetrics().density);
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (hostStarted) nativeSurfaceCreated(holder.getSurface(), getResources().getDisplayMetrics().density);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        // The supported host API acknowledges surface release before Android disposes it.
        if (hostStarted) nativeSurfaceDestroyed();
    }

    private boolean forwardTouch(MotionEvent event) {
        int count = event.getPointerCount();
        if (!hostStarted || count < 1 || count > 16) return false;
        int[] ids = new int[count];
        float[] xs = new float[count], ys = new float[count];
        for (int i = 0; i < count; i++) {
            ids[i] = event.getPointerId(i);
            xs[i] = event.getX(i);
            ys[i] = event.getY(i);
        }
        return nativeTouch(event.getActionMasked(), event.getActionIndex(), ids, xs, ys);
    }

    @Override protected void onResume() {
        super.onResume();
        if (isFinishing() || launch == null || shortcutJson == null || !hostStarted) return;
        nativeResumed();
        synchronized (stateLock) {
            if (connection != null) return;
            Connection next;
            if (launch.remoteHost != null) {
                long remoteHandle = launch.takeRemoteHandle();
                if (remoteHandle <= 0) {
                    Toast.makeText(this, "Remote session was paused. Reconnect from Remote hosts", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                next = new Connection(GENERATIONS.incrementAndGet(), remoteHandle);
            } else next = new Connection(GENERATIONS.incrementAndGet(), new CompanionClient(launch.host.profile));
            connection = next;
            nativeSetActive(next.generation, false);
            publish(next, "Connecting…");
            next.poll = worker.scheduleWithFixedDelay(() -> pump(next), 0, COMMAND_POLL_MS, TimeUnit.MILLISECONDS);
        }
    }

    @Override protected void onPause() {
        allowNextKeyboardShow = false;
        oneShotKey = KeyEvent.KEYCODE_UNKNOWN;
        clearControl();
        if (displayDialog != null) displayDialog.dismiss();
        capturedKeys.clear();
        stopConnection();
        if (hostStarted) nativePaused();
        super.onPause();
    }

    @Override protected void onStop() {
        stopConnection();
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        stopConnection();
        worker.shutdownNow();
        GpuiLaunch.release(launch);
        launch = null;
        shortcutJson = null;
        shortcuts = Collections.emptyList();
        if (commandPalette != null) commandPalette.close();
        commandPalette = null;
        lastSnapshotTitle = lastSnapshotText = lastSnapshotStatus = lastSnapshotShortcuts = lastSnapshotSurface = null;
        terminalRoot = null;
        super.onDestroy();
    }

    /** OSC 52 text the daemon forwarded: plain text only, never a URI or bytes. */
    private void applyRemoteClipboard(Connection target, String text) {
        synchronized (stateLock) {
            if (!current(target)) return;
        }
        android.content.ClipboardManager manager =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager == null) return;
        try {
            manager.setPrimaryClip(android.content.ClipData.newPlainText("Herdr", text));
            Toast.makeText(this, "Copied from terminal", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException rejected) {
            // A locked device may refuse clipboard writes; the session stays usable.
        }
    }

    /** Opens a terminal link through the platform browser; never a file or custom scheme. */
    public void gpuiOpenLink(long expectedGeneration, String url) {
        runOnUiThread(() -> {
            synchronized (stateLock) {
                if (destroyed || isFinishing() || connection == null
                        || connection.generation != expectedGeneration) return;
            }
            if (url == null || url.length() > 2048) return;
            String lower = url.toLowerCase(java.util.Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return;
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException noBrowser) {
                Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Called by GPUI directly, independent of the HTTP command queue and connection health. */
    public void gpuiClose(long expectedGeneration) {
        runOnUiThread(() -> {
            synchronized (stateLock) {
                if (connection != null && connection.generation == expectedGeneration) finish();
            }
        });
    }

    /** Only the explicit GPUI Keyboard control grants a show request. Focus alone does not. */
    public void gpuiRequestKeyboard(long expectedGeneration) {
        runOnUiThread(() -> {
            synchronized (stateLock) {
                if (destroyed || isFinishing() || connection == null || !connection.ready
                        || connection.isClosed() || connection.generation != expectedGeneration
                        || displayDialog != null && displayDialog.isShowing()) return;
                keyboardRequested = true;
                allowNextKeyboardShow = true;
            }
        });
    }

    @Override public void gpuiShowKeyboard(int keyboardType, long session) {
        runOnUiThread(() -> {
            if (!allowNextKeyboardShow || session < imeSession
                    || displayDialog != null && displayDialog.isShowing()) return;
            allowNextKeyboardShow = false;
            clearControl();
            setImeSession(session);
            synchronized (stateLock) {
                if (!destroyed && !isFinishing() && connection != null
                        && !connection.isClosed() && connection.ready) {
                    super.gpuiShowKeyboard(keyboardType, session);
                }
            }
        });
    }

    @Override public void gpuiHideKeyboard(long session) {
        runOnUiThread(() -> {
            if (session < imeSession) return;
            setImeSession(session);
            if (!destroyed) super.gpuiHideKeyboard(session);
        });
    }

    @Override public void gpuiResetComposition(long session) {
        runOnUiThread(() -> {
            if (session < imeSession) return;
            setImeSession(session);
            if (!destroyed) super.gpuiResetComposition(session);
        });
    }

    private void setImeSession(long session) {
        imeSession = session;
        imeComposing = false;
        synchronized (stateLock) {
            if (!destroyed && connection != null) nativeSetImeSession(connection.generation, session);
        }
    }

    @Override protected void gpuiIme(long session, int kind, String text, int start, int end) {
        clearControl();
        if (displayDialog != null && displayDialog.isShowing()) return;
        synchronized (stateLock) {
            Connection target = connection;
            if (destroyed || session != imeSession || target == null || target.isClosed() || !target.ready) return;
            if (kind == 0) imeComposing = !text.isEmpty();
            else if (kind == 1 || kind == 4) imeComposing = false;
            // Commit is synchronously queued before this callback returns. Never also forward
            // through upstream's deferred nativeIme, which could reorder or duplicate remote input.
            if (!nativeImeEvent(target.generation, session, kind, text, start, end)) {
                fail(target, "Input was not queued; reopen and check the terminal before retrying");
            }
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        // Never persist host credentials, terminal text, IME drafts, or a reusable launch grant.
        // Also skip the framework view-state traversal of the invisible IME proxy.
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (displayDialog != null && displayDialog.isShowing()) return super.dispatchKeyEvent(event);
        boolean physical = event.isFromSource(InputDevice.SOURCE_KEYBOARD)
                && event.getDevice() != null && !event.getDevice().isVirtual()
                && (event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) == 0;
        int code = event.getKeyCode();
        if (!physical || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN || event.isSystem()) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            capturedKeys.remove(code);
            if (oneShotKey == code) oneShotKey = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }
        // A one-shot Ctrl press must not turn into plain text when the same key repeats.
        if (event.getAction() == KeyEvent.ACTION_DOWN && oneShotKey == code) {
            if (event.getRepeatCount() > 0) return true;
            oneShotKey = KeyEvent.KEYCODE_UNKNOWN;
        }
        if (KeyEvent.isModifierKey(code) || code == KeyEvent.KEYCODE_CAPS_LOCK
                || code == KeyEvent.KEYCODE_NUM_LOCK || code == KeyEvent.KEYCODE_SCROLL_LOCK
                || event.getAction() != KeyEvent.ACTION_DOWN) return true;
        capturedKeys.add(code);
        synchronized (stateLock) {
            Connection target = connection;
            if (target == null || target.isClosed()) { clearControl(); return true; }
            if (!target.ready) {
                publish(target, "Wait for the terminal connection before typing");
                return true;
            }
            if (compositionBlocksInput(target)) { clearControl(); return true; }
            try {
                if (event.isMetaPressed() || event.isSymPressed() || event.isFunctionPressed()) {
                    clearControl();
                    throw new IllegalArgumentException("Unsupported modifier");
                }
                String named = physicalKeyName(code);
                boolean oneShot = controlLatched;
                clearControl();
                if (oneShot) oneShotKey = code;
                boolean control = event.isCtrlPressed() || oneShot;
                boolean terminalAlt = event.isAltPressed() && !displayOptions.altUsesSymbols();
                // OEM Alt symbols come from this event's KeyCharacterMap. Never guess digits,
                // scancodes, Fn meanings, or modify the user's system keyboard settings.
                if (event.isAltPressed() && (control || named != null))
                    throw new IllegalArgumentException("Unsupported modifiers");
                boolean accepted;
                if (named != null || control || terminalAlt) {
                    String key = named == null ? String.valueOf((char) event.getUnicodeChar(0)) : named;
                    String canonical = KeyChord.fromPhysical(key, control, terminalAlt, event.isShiftPressed());
                    accepted = nativeSendKey(target.generation, canonical);
                } else {
                    int unicode = event.getUnicodeChar(event.getMetaState());
                    if ((unicode & KeyCharacterMap.COMBINING_ACCENT) != 0) {
                        fail(target, "Physical dead-key composition is unsupported; reopen and use the on-screen keyboard");
                        return true;
                    }
                    String value = GpuiTerminalProtocol.physicalText(unicode);
                    accepted = event.isAltPressed()
                            ? nativeSendCommand(target.generation, new org.json.JSONObject()
                                    .put("type", "text").put("text", value).toString())
                            : nativeKey(target.generation, code, event.getAction(), event.getMetaState(), unicode);
                }
                // Every physical key bypasses the IME proxy and enters this one native FIFO.
                // Otherwise an immediate Enter could overtake text deferred to the render thread.
                if (!accepted) fail(target, "Input was not queued; reopen and check the terminal before retrying");
            } catch (IllegalArgumentException | JSONException unsupported) {
                publish(target, "This key combination is not supported; use the on-screen keyboard for composed characters");
            }
        }
        return true;
    }

    private boolean compositionBlocksInput(Connection target) {
        View focus = getCurrentFocus();
        boolean marked = focus instanceof EditText
                && BaseInputConnection.getComposingSpanStart(((EditText) focus).getText()) >= 0;
        if (!imeComposing && !marked) return false;
        publish(target, "Finish or cancel on-screen composition before sending other keys");
        return true;
    }

    private static String physicalKeyName(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return "enter";
            case KeyEvent.KEYCODE_ESCAPE: return "esc";
            case KeyEvent.KEYCODE_TAB: return "tab";
            case KeyEvent.KEYCODE_DEL: return "backspace";
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            default: return key >= KeyEvent.KEYCODE_F1 && key <= KeyEvent.KEYCODE_F12
                    ? "f" + (key - KeyEvent.KEYCODE_F1 + 1) : null;
        }
    }

    private void stopConnection() {
        final Connection old;
        synchronized (stateLock) {
            old = connection;
            connection = null;
            if (old == null) return;
            old.cancel();
            if (old.poll != null) old.poll.cancel(false);
            nativeSetActive(old.generation, false);
            old.text = "";
            publish(old, "Disconnected; unsent input discarded");
        }
        // disconnect() can involve platform I/O. Never join or perform it on the UI thread.
        Thread cleanup = new Thread(old::close, "herdr-gpui-disconnect");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    private boolean current(Connection candidate) {
        synchronized (stateLock) {
            return connection == candidate && !candidate.isClosed();
        }
    }

    private void pump(Connection current) {
        if (!current(current)) return;
        if (current.remoteHandle != 0) { pumpRemote(current); return; }
        boolean mutation = false;
        try {
            String raw = nativeTakeCommand(current.generation);
            if (raw != null) {
                GpuiTerminalProtocol.Command command = GpuiTerminalProtocol.command(raw);
                if ("close".equals(command.type)) {
                    synchronized (stateLock) {
                        if (!current(current)) return;
                        nativeSetActive(current.generation, false);
                        if (current.poll != null) current.poll.cancel(false);
                    }
                    main.post(() -> { if (current(current)) finish(); });
                    return;
                }
                if (!current(current)) return;
                mutation = true;
                if ("submit".equals(command.type)) {
                    GpuiTerminalProtocol.requireAck(current.client.sendText(current.paneId, command.text));
                    // One FIFO item owns both requests. Never submit after an uncertain text
                    // acknowledgment or after this connection was paused/replaced.
                    if (!current(current)) return;
                    GpuiTerminalProtocol.requireAck(current.client.sendKeys(current.paneId,
                            Collections.singletonList("enter")));
                } else {
                    String response = "text".equals(command.type)
                            ? current.client.sendText(current.paneId, command.text)
                            : current.client.sendKeys(current.paneId, command.keys);
                    GpuiTerminalProtocol.requireAck(response);
                }
                synchronized (stateLock) {
                    if (!current(current)) return;
                    publish(current, "Input accepted by companion");
                }
                mutation = false;
            }
            long now = SystemClock.elapsedRealtime();
            if (now < current.nextScreenAt || !current(current)) return;
            String text = GpuiTerminalProtocol.screen(current.client.getScreen(current.paneId));
            synchronized (stateLock) {
                if (!current(current)) return;
                current.text = text;
                current.ready = true;
                current.nextScreenAt = SystemClock.elapsedRealtime() + SCREEN_POLL_MS;
                publish(current, "Connected · " + launch.name());
                nativeSetActive(current.generation, true);
            }
        } catch (ClientException failure) {
            fail(current, failure.getMessage());
        } catch (IOException | IllegalArgumentException invalid) {
            fail(current, mutation
                    ? "Host acknowledgment was invalid. Input may have reached the host; reopen and check before retrying"
                    : "Invalid or oversized terminal data; reopen to connect again");
        } catch (RuntimeException failure) {
            fail(current, mutation
                    ? "Input failed and may have reached the host; reopen and check before retrying"
                    : "Terminal connection stopped; reopen to connect again");
        }
    }

    private void pumpRemote(Connection current) {
        boolean mutation = false;
        try {
            String raw = nativeTakeCommand(current.generation);
            if (raw != null) {
                GpuiTerminalProtocol.Command command = GpuiTerminalProtocol.command(raw);
                if ("close".equals(command.type)) {
                    synchronized (stateLock) {
                        if (!current(current)) return;
                        current.ready = false;
                        nativeSetActive(current.generation, false);
                        if (current.poll != null) current.poll.cancel(false);
                    }
                    main.post(() -> { if (current(current)) finish(); });
                    return;
                }
                if (!current(current) || !current.ready) return;
                mutation = true;
                String result = RemoteNative.input(current.remoteHandle, current.paneId, raw);
                if (!new JSONObject(result).optBoolean("queued", false)) throw new IOException("Input was not queued");
                // Binary input has no daemon receipt. Submit is one TextCommit+Enter
                // batch; this status deliberately means queued, never acknowledged.
                synchronized (stateLock) {
                    if (current(current)) publish(current, "Input queued over SSH");
                }
                mutation = false;
            }
            if (!current(current)) return;
            String rawState = RemoteNative.poll(current.remoteHandle);
            if (rawState == null || rawState.length() > 1024 * 1024) throw new IOException("Oversized remote state");
            JSONObject state = new JSONObject(rawState);
            String phase = state.getString("state");
            if ("closed".equals(phase)) {
                fail(current, GpuiTerminalProtocol.title(state.optString("status", "Remote session closed")));
                return;
            }
            if (!"ready".equals(phase) && !"connecting".equals(phase)) throw new IOException("Invalid remote state");
            String pane = state.optString("pane_id", "");
            String boot = state.optString("boot_id", "");
            // The daemon owns the focused pane: a tab, workspace or pane switch
            // moves it, and the terminal follows instead of waiting forever.
            if (!pane.isEmpty()) current.paneId = pane;
            boolean selected = "ready".equals(phase) && !pane.isEmpty() && !boot.isEmpty();
            String text = selected ? state.getString("text") : "";
            synchronized (stateLock) {
                if (!current(current)) return;
                if (current.remoteBoot != null && !current.remoteBoot.equals(boot)) {
                    fail(current, "Herdr restarted. Reopen the remote session before sending input");
                    return;
                }
                if (selected) current.remoteBoot = boot;
                current.text = text;
                current.ready = selected;
                current.surface = selected ? state.optString("surface", "") : "";
                current.workspaces = state.optJSONArray("workspaces");
                current.tabs = state.optJSONArray("tabs");
                current.panes = state.optJSONArray("panes");
                current.chromePane = pane;
                long serial = state.optLong("clipboard_serial", 0);
                String copy = state.optString("clipboard", "");
                if (serial > current.clipboardSerial) {
                    current.clipboardSerial = serial;
                    // Only bounded UTF-8 text decoded by the native side reaches the pasteboard.
                    if (!copy.isEmpty()) {
                        final Connection owner = current;
                        main.post(() -> applyRemoteClipboard(owner, copy));
                    }
                }
                publish(current, selected ? "SSH connected · " + launch.name() : "Waiting for selected remote pane…");
                nativeSetActive(current.generation, selected);
            }
            main.post(() -> refreshSidebar(false));
        } catch (IOException | JSONException | RuntimeException failure) {
            fail(current, mutation
                    ? "SSH input stopped; it may have reached the host. Reconnect and inspect before retrying"
                    : "Remote session stopped. Reconnect from Remote hosts");
        }
    }

    private void fail(Connection failed, String message) {
        synchronized (stateLock) {
            if (!current(failed)) return;
            failed.cancel();
            if (failed.poll != null) failed.poll.cancel(false);
            nativeSetActive(failed.generation, false);
            publish(failed, message);
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread cleanup = new Thread(failed::close, "herdr-gpui-disconnect");
            cleanup.setDaemon(true);
            cleanup.start();
        } else failed.close();
    }

    /** Called only under stateLock, preventing a paused session from reactivating after a read. */
    private void publish(Connection target, String status) {
        if (target.generation == lastSnapshotGeneration && target.title.equals(lastSnapshotTitle)
                && target.text.equals(lastSnapshotText) && status.equals(lastSnapshotStatus)
                && shortcutJson.equals(lastSnapshotShortcuts) && target.surface.equals(lastSnapshotSurface)) return;
        nativeSetSnapshot(target.generation, target.title, target.text, status, shortcutJson, target.surface);
        lastSnapshotGeneration = target.generation;
        lastSnapshotTitle = target.title;
        lastSnapshotText = target.text;
        lastSnapshotStatus = status;
        lastSnapshotShortcuts = shortcutJson;
        lastSnapshotSurface = target.surface;
    }

    private final class Connection {
        final long generation;
        final CompanionClient client;
        final long remoteHandle;
        volatile boolean closed;
        String remoteBoot;
        volatile String paneId = launch.paneId;
        final String title = launch.title;
        String text = "";
        String surface = "";
        JSONArray workspaces, tabs, panes;
        volatile long clipboardSerial;
        String chromePane = "";
        long nextScreenAt;
        boolean ready;
        ScheduledFuture<?> poll;

        Connection(long generation, CompanionClient client) {
            this.generation = generation;
            this.client = client;
            this.remoteHandle = 0;
        }
        Connection(long generation, long remoteHandle) {
            this.generation = generation;
            this.client = null;
            this.remoteHandle = remoteHandle;
        }
        boolean isClosed() { return closed || (client != null && client.isClosed()); }
        void cancel() {
            closed = true;
            if (client != null) client.cancel();
            else RemoteNative.close(remoteHandle);
        }
        void close() {
            closed = true;
            if (client != null) client.close();
            else RemoteNative.close(remoteHandle);
        }
    }

    private static native void nativeSetDisplayPreferences(float fontSize, boolean compact);
    private static native void nativeSetTheme(int index);
    private static native void nativeSetSnapshot(long generation, String title, String text, String status,
            String shortcutsJson, String surfaceJson);
    private static native void nativeSetActive(long generation, boolean active);
    private static native String nativeTakeCommand(long generation);
    private static native boolean nativeSendCommand(long generation, String commandJson);
    private static native boolean nativeSendKey(long generation, String key);
    private static native void nativeStart(android.app.Activity activity);
    private static native void nativeSurfaceCreated(Surface surface, float scale);
    private static native void nativeSurfaceDestroyed();
    private static native void nativeResumed();
    private static native void nativePaused();
    private static native boolean nativeTouch(int action, int actionIndex, int[] ids, float[] xs, float[] ys);
    private static native boolean nativeKey(long generation, int keyCode, int action, int metaState, int unicodeCodepoint);
    private static native void nativeSetImeSession(long generation, long session);
    private static native boolean nativeImeEvent(long generation, long session, int kind, String text, int start, int end);
}
