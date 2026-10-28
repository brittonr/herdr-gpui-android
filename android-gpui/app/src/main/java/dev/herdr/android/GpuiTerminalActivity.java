package dev.herdr.android;

import android.annotation.SuppressLint;

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
    /** Loop period for input commands; the poll gate below decides frame cadence. */
    private static final long COMMAND_POLL_MS = 16;
    private static final long SCREEN_POLL_MS = 1000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object stateLock = new Object();
    private final Set<Integer> capturedKeys = new HashSet<>();
    /** Scroll requests ride their own thread so a poll parse never delays them. */
    private final java.util.concurrent.ExecutorService scrollWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "herdr-scroll");
                thread.setDaemon(true);
                return thread;
            });
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
    /** Reused rail rows, so a sampler refresh never replaces the view under a finger. */
    private final java.util.Map<String, RailHostRow> railHostRows = new java.util.HashMap<>();
    private final java.util.Map<String, RailWorkspaceRow> railWorkspaces = new java.util.HashMap<>();
    private final java.util.Map<String, RailPaneRow> railPanes = new java.util.HashMap<>();
    private final java.util.Map<String, TextView> railNotes = new java.util.HashMap<>();
    private final java.util.Map<String, View> railRules = new java.util.HashMap<>();

    private ScrollView sidebarScroll;
    private Button sidebarButton;
    private volatile boolean sidebarOpen;
    private String sidebarSignature = "";
    private QuickCommandPalette.Owner paletteOwner;
    private volatile java.util.List<SavedRemoteHost> savedHosts = java.util.Collections.emptyList();
    private volatile String currentHostId;
    private volatile boolean switchingHost;
    /** Host currently being adopted; its sampler stays closed meanwhile. */
    private volatile String switchingTo;
    /** One background SSH session per saved host, used only to mirror its tree. */
    private static final class HostSample {
        volatile long handle;
        volatile org.json.JSONArray workspaces, tabs, panes;
        volatile boolean connected;
        volatile long revision = -1;
        volatile long nextAttemptAt;
    }
    private final java.util.Map<String, HostSample> hostSamples = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.concurrent.ScheduledExecutorService sampler;
    private AlertDialog displayDialog;
    private Button controlButton;
    /** Vertical swipe state: a short tap still reaches the terminal. */
    private float touchDownX, edgeStartX;
    private boolean edgeClaimed;
    private float touchDownY, lastTouchY, scrollFraction;
    private float scrollVelocity, flingRemainder;
    private long lastMoveAt;
    private boolean flinging;
    private SurfaceView terminalSurface;
    private final Runnable flingStep = new Runnable() {
        @Override public void run() {
            if (!flinging || terminalSurface == null) return;
            long now = SystemClock.uptimeMillis();
            long dt = Math.max(1, Math.min(48, now - lastMoveAt));
            lastMoveAt = now;
            float rowPx = rowHeightPx();
            float lines = scrollVelocity * dt / rowPx + flingRemainder;
            int step = (int) lines;
            flingRemainder = lines - step;
            // Sub-row frames still count: they carry into the next one.
            boolean atEdge = step != 0 && !requestScroll(step);
            scrollVelocity *= (float) Math.pow(0.90, dt / 16.0);
            if (!atEdge && Math.abs(scrollVelocity) > 0.05f && rowPx > 0f) {
                terminalSurface.postOnAnimation(this);
                return;
            }
            flinging = false;
            scrollVelocity = 0f;
            flingRemainder = 0f;
            flushScroll();
        }
    };

    private void stopFling() {
        flinging = false;
        scrollVelocity = 0f;
        flingRemainder = 0f;
    }
    private boolean scrolling;
    /** Locally accumulated scrollback target; polls only resync it while idle. */
    private long desiredScroll, lastSentScroll = -1;
    /** Rows requested but not yet drawn, so the local buffer stays ahead. */
    private static final long PREFETCH_WINDOW = 32;
    /** True while the finger, a fling, or an in-flight request owns the offset. */
    private volatile boolean scrollSeeking;
    private boolean lastFollowSent = true;
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
        // Terminal content is private. Only a debuggable build that opts in through
        // the global switch may be captured, so a field report can be screenshotted
        // without weakening a release build.
        boolean capturable = (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && "1".equals(android.provider.Settings.Global.getString(
                        getContentResolver(), "herdr_probe"));
        if (!capturable) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
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
            paletteOwner = new QuickCommandPalette.Owner() {
                public void show(AlertDialog dialog) { showLocalDialog(dialog); }
                public boolean isCurrentDialog(AlertDialog dialog) {
                    return !destroyed && !isFinishing() && displayDialog == dialog && dialog.isShowing();
                }
            };
            commandPalette = launch.remoteHost == null
                    ? new QuickCommandPalette(this, launch.host, launch.title, paletteOwner)
                    : new QuickCommandPalette(this, launch.remoteHost, launch.title, paletteOwner);
            currentHostId = launch.remoteHost == null ? null : launch.remoteHost.id;
            // Remote-mutating controls live on the Java UI thread, never a deferred GPUI click.
            shortcutJson = "[]";
            loadDisplayOptions();
            themeIndex = getSharedPreferences("terminal-display", MODE_PRIVATE).getInt("theme", 0);
            if (themeIndex < 0 || themeIndex > 3) themeIndex = 0;
            nativeStart(this);
            nativeSetTheme(themeIndex);
            // Host switching needs the saved hosts before the first rail paint.
            worker.execute(() -> {
                try {
                    java.util.List<SavedRemoteHost> hosts = new RemoteHostStore(this).read();
                    runOnUiThread(() -> { savedHosts = hosts; startHostSampling(); refreshSidebar(true); });
                } catch (Exception ignored) {
                    // An unreadable store leaves the rail without host rows.
                }
            });
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

    // The painted grid is a gesture surface, not a click target: a screen reader
    // reaches this app through the toolbar, the rail and the Keys keyboard.
    @SuppressLint("ClickableViewAccessibility")
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
        terminalSurface = surface;
        surface.setFocusable(true);
        surface.setFocusableInTouchMode(true);
        surface.setSaveEnabled(false);
        surface.getHolder().addCallback(this);
        surface.setOnTouchListener((view, event) -> forwardTouch(event));
        // Android's Back gesture owns the screen edges. Excluding a narrow left
        // strip lets an edge swipe reach the terminal, where it opens the rail.
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        terminalContent = content;
        content.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(createShortcutBar(), new LinearLayout.LayoutParams(-1, dp(48)));
        // The sidebar rail mirrors the desktop's list of workspaces and agents.
        sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.HORIZONTAL);
        sidebar.setBackgroundColor(0xff12161d);
        sidebar.setSaveFromParentEnabled(false);
        sidebarScroll = new RailScrollView(this, this::hideSidebar);
        sidebarScroll.setBackgroundColor(0xff12161d);
        sidebarScroll.setVerticalScrollBarEnabled(false);
        sidebarRows = new LinearLayout(this);
        sidebarRows.setOrientation(LinearLayout.VERTICAL);
        sidebarRows.setPadding(dp(8), dp(10), dp(8), dp(20));
        sidebarScroll.addView(sidebarRows, new android.widget.FrameLayout.LayoutParams(-1, -2));
        // Hosts and workspaces exceed one screen; the fade says the list keeps going.
        android.widget.FrameLayout railFrame = new android.widget.FrameLayout(this);
        railFrame.addView(sidebarScroll, new android.widget.FrameLayout.LayoutParams(-1, -1));
        View railFade = new View(this);
        railFade.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x0012161d, 0xff12161d}));
        railFade.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                -1, dp(24), android.view.Gravity.BOTTOM));
        railFrame.addView(railFade);
        sidebar.addView(railFrame, new LinearLayout.LayoutParams(0, -1, 1));
        View railEdge = new View(this);
        railEdge.setBackgroundColor(0xff1c222b);
        sidebar.addView(railEdge, new LinearLayout.LayoutParams(dp(1), -1));
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


    /**
     * Real terminal grid for the daemon: the monospace cell box the renderer
     * uses, so Herdr sizes the pane to this display instead of 80x24.
     */
    private String terminalGrid() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int stored = getSharedPreferences("terminal-display", MODE_PRIVATE).getInt("font", 13);
        float fontPx = Math.max(8f, (stored > 0 ? stored : 13) * getResources()
                .getConfiguration().fontScale * dm.density / dm.scaledDensity * dm.density);
        int cellW = Math.max(1, Math.round(fontPx * 0.6f));
        int cellH = Math.max(1, Math.round(fontPx * 1.25f));
        int cols = Math.min(400, Math.max(20, dm.widthPixels / cellW));
        int rows = Math.min(200, Math.max(5, (dm.heightPixels - dp(48) * 3) / cellH));
        return cols + "," + rows + "," + cellW + "," + cellH;
    }


    private LinearLayout terminalContent;
    private LinearLayout banner;
    private TextView bannerText;

    /** Explains a dead connection and offers the one action that can fix it. */
    private void showReconnectBanner(String message) {
        // fail() runs on the poll worker; Android views may only be touched on
        // the main thread, so marshal first instead of throwing silently there.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread(() -> showReconnectBanner(message));
            return;
        }
        if (terminalRoot == null) return;
        if (banner == null) {
            banner = new LinearLayout(this);
            banner.setOrientation(LinearLayout.HORIZONTAL);
            banner.setGravity(android.view.Gravity.CENTER_VERTICAL);
            banner.setBackgroundColor(0xff2a2024);
            banner.setPadding(dp(16), dp(4), dp(8), dp(4));
            bannerText = new TextView(this);
            bannerText.setTextColor(0xffe0a0a0);
            bannerText.setTextSize(13);
            LinearLayout.LayoutParams textParams =
                    new LinearLayout.LayoutParams(0, -2, 1f);
            banner.addView(bannerText, textParams);
            Button hosts = new Button(this);
            hosts.setText("Hosts");
            hosts.setAllCaps(false);
            hosts.setTextSize(13);
            hosts.setTextColor(0xffdce5ef);
            hosts.setBackgroundTintList(ColorStateList.valueOf(0xff303744));
            hosts.setMinHeight(dp(48));
            hosts.setStateListAnimator(null);
            hosts.setOnClickListener(view -> startActivity(new android.content.Intent(this,
                    RemoteHostsActivity.class).putExtra("skip_reopen", true)));
            banner.addView(hosts, new LinearLayout.LayoutParams(-2, dp(48)));
            Button retry = new Button(this);
            retry.setText("Reconnect");
            retry.setAllCaps(false);
            retry.setTextSize(13);
            retry.setTextColor(0xffdce5ef);
            retry.setBackgroundTintList(ColorStateList.valueOf(0xff303744));
            retry.setMinHeight(dp(48));
            retry.setStateListAnimator(null);
            retry.setOnClickListener(view -> reconnectCurrentHost());
            banner.addView(retry, new LinearLayout.LayoutParams(-2, dp(48)));
            banner.setVisibility(View.GONE);
            // Directly above the toolbar: closest to the surface it explains.
            int barIndex = terminalContent.getChildCount() - 1;
            terminalContent.addView(banner, barIndex);
        }
        bannerText.setText(humanReason(message));
        banner.setVisibility(View.VISIBLE);
    }


    /** Transport wording is for logs; the banner says what happened and what to do. */

    /** One description for a host row: name, workspace count, then its state. */
    private String hostDescription(RailHostRow holder) {
        StringBuilder text = new StringBuilder("Connect to ").append(holder.host.name);
        if (holder.counted > 0) text.append(", ").append(holder.counted).append(" workspaces");
        if (holder.hostState != null && !holder.hostState.isEmpty()) {
            text.append(", ").append(holder.hostState);
        }
        return text.toString();
    }

    private String humanReason(String raw) {
        if (raw == null || raw.isEmpty()) return "Connection lost";
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("health check")) return "Connection lost · the host stopped answering";
        if (lower.contains("timed out")) return "Connection lost · the host timed out";
        if (lower.contains("closed") || lower.contains("ended") || lower.contains("disconnected")) {
            return "Connection lost";
        }
        return "Connection lost · " + raw;
    }

    private void hideReconnectBanner() {
        if (banner != null) banner.setVisibility(View.GONE);
    }

    /** Reuses the saved host for this session; no credential is kept in memory. */
    private void reconnectCurrentHost() {
        final String id = currentHostId;
        hideReconnectBanner();
        publishCurrent("Reconnecting…");
        worker.execute(() -> {
            SavedRemoteHost host = null;
            try {
                // The rail's cached list is often empty here; the store is truth.
                for (SavedRemoteHost candidate : new RemoteHostStore(this).read()) {
                    if (candidate.id.equals(id)) host = candidate;
                }
            } catch (Exception ignored) {
                host = null;
            }
            if (host == null) {
                runOnUiThread(() -> showReconnectBanner("Open the host list to reconnect"));
                return;
            }
            long handle = 0;
            try {
                char[] key = new RemoteIdentityStore(this).read();
                String credential;
                try {
                    credential = new JSONObject().put("private_key", new String(key)).toString();
                } finally {
                    java.util.Arrays.fill(key, '\0');
                }
                handle = RemoteNative.connectGrid(host.connectionJson(), credential, terminalGrid());
                if (handle <= 0) throw new IllegalStateException("connection refused");
                String chosen = awaitHostPane(handle, host.id, null);
                final long readyHandle = handle;
                final String readyPane = chosen;
                final SavedRemoteHost readyHost = host;
                runOnUiThread(() -> { hideReconnectBanner(); adoptHost(readyHost, readyHandle, readyPane); });
                handle = 0;
            } catch (Exception | LinkageError failure) {
                if (handle != 0) RemoteNative.close(handle);
                runOnUiThread(() -> showReconnectBanner("Reconnect failed. Check the host and network."));
            }
        });
    }

    private void publishCurrent(String status) {
        Connection target;
        synchronized (stateLock) { target = connection; }
        if (target != null && !target.isClosed()) publish(target, status);
    }


    private View createShortcutBar() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        shell.setBackgroundColor(0xff1e222a);
        // The three controls reached constantly stay put; the rest scroll, so
        // nothing can be scrolled out of reach on a narrow panel.
        LinearLayout pinned = new LinearLayout(this);
        pinned.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        sidebarButton = localButton("Hosts");
        sidebarButton.setContentDescription("Show or hide the workspace and agent sidebar");
        sidebarButton.setOnClickListener(view -> toggleSidebar());
        synchronousTouch(sidebarButton);
        pinned.addView(sidebarButton, new LinearLayout.LayoutParams(dp(64), dp(48)));
        Button keys = localButton("Keys");
        keys.setContentDescription("Show the on-screen keyboard");
        keys.setOnClickListener(view -> {
            Connection target;
            synchronized (stateLock) { target = connection; }
            if (target != null) nativeShowKeyboard(target.generation);
        });
        synchronousTouch(keys);
        pinned.addView(keys, new LinearLayout.LayoutParams(dp(52), dp(48)));
        Button back = localButton("Back");
        back.setContentDescription("Close this terminal and return to the host list");
        back.setOnClickListener(view -> finish());
        synchronousTouch(back);
        pinned.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));
        Button options = localButton("View");
        options.setContentDescription("Terminal display and keyboard options");
        options.setOnClickListener(view -> showDisplayOptions());
        synchronousTouch(options);
        bar.addView(options, new LinearLayout.LayoutParams(dp(56), dp(48)));
        Button commands = localButton("Cmds");
        commands.setContentDescription("Quick commands for this host");
        commands.setOnClickListener(view -> showQuickCommands());
        synchronousTouch(commands);
        bar.addView(commands, new LinearLayout.LayoutParams(dp(58), dp(48)));
        Button panes = localButton("Panes");
        panes.setContentDescription("Workspaces, tabs and panes for this host");
        panes.setOnClickListener(view -> showPanes());
        synchronousTouch(panes);
        bar.addView(panes, new LinearLayout.LayoutParams(dp(54), dp(48)));
        // "Tab" belongs to the key beside it; this one opens session actions.
        Button session = localButton("Tabs");
        session.setContentDescription("New tab, split, zoom or close this session's pane");
        session.setOnClickListener(view -> showSessionActions());
        synchronousTouch(session);
        bar.addView(session, new LinearLayout.LayoutParams(dp(50), dp(48)));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int gap = Math.round(4 * getResources().getDisplayMetrics().density);
        row.setPadding(0, 0, gap, 0);
        // Panning is client-side: it is how a wide pane stays readable at a type
        // size that actually fits this display.
        for (float panStep : new float[]{-96f, 96f}) {
            Button pan = new Button(this);
            pan.setText(panStep < 0 ? "\u25C0" : "\u25B6");
            pan.setTextSize(12);
            pan.setTextColor(0xffe7eaf0);
            pan.setBackgroundTintList(ColorStateList.valueOf(0xff303744));
            pan.setAllCaps(false);
            pan.setMinWidth(0);
            pan.setMinimumWidth(0);
            pan.setFocusable(false);
            pan.setSaveEnabled(false);
            pan.setContentDescription(panStep < 0 ? "Pan left" : "Pan right");
            final float step = panStep;
            pan.setOnClickListener(view -> nativePan(step));
            synchronousTouch(pan);
            LinearLayout.LayoutParams panParams = new LinearLayout.LayoutParams(dp(44), dp(48));
            int panGap = Math.round(4 * getResources().getDisplayMetrics().density);
            panParams.setMargins(panGap, 0, panGap, 0);
            row.addView(pan, panParams);
        }
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
        // The key strip scrolls sideways; an edge fade says so instead of showing
        // a button sliced in half at the boundary.
        bar.addView(row, new LinearLayout.LayoutParams(-2, dp(48)));
        HorizontalScrollView outer = new HorizontalScrollView(this);
        outer.setHorizontalScrollBarEnabled(false);
        outer.setBackgroundColor(0xff1e222a);
        outer.addView(bar, new FrameLayout.LayoutParams(-2, dp(48)));
        shell.addView(pinned, new LinearLayout.LayoutParams(-2, dp(48)));
        // The scroller cuts its last control in half; the fade marks the boundary
        // as "swipe for more" instead of a rendering fault.
        FrameLayout scrolling = new FrameLayout(this);
        scrolling.addView(outer, new FrameLayout.LayoutParams(-1, dp(48)));
        View fade = new View(this);
        fade.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT,
                new int[]{0xff1e222a, 0x001e222a}));
        fade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams fadeParams = new FrameLayout.LayoutParams(dp(20), dp(48));
        fadeParams.gravity = android.view.Gravity.END;
        scrolling.addView(fade, fadeParams);
        shell.addView(scrolling, new LinearLayout.LayoutParams(0, dp(48), 1));
        return shell;
    }

    // performClick is dispatched from the touch lambda below; lint cannot see it.
    @SuppressLint("ClickableViewAccessibility")
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

    private float rowHeightPx() {
        long rows;
        synchronized (stateLock) { rows = connection == null ? 0 : connection.scrollRows; }
        if (rows > 0 && terminalRoot != null) {
            int height = terminalRoot.getHeight() - terminalRoot.getPaddingTop() - terminalRoot.getPaddingBottom();
            if (height > 0) return Math.max(1f, (float) height / rows);
        }
        float font = displayOptions == null ? 13f
                : displayOptions.effectiveFont(compactDisplay, getResources().getConfiguration().fontScale);
        float lineHeight = compactDisplay ? 1.4f : 1.5f;
        return Math.max(1f, font * lineHeight * getResources().getDisplayMetrics().density);
    }

    /** Drag down reveals older lines: the client scrolls its own buffered rows. */
    private boolean requestScroll(int lines) {
        final Connection target;
        synchronized (stateLock) { target = connection; }
        if (target == null || target.isClosed() || target.remoteHandle <= 0) return false;
        if (lines > 0 && lastFollowSent) {
            lastFollowSent = false;
            nativeSetFollow(target.generation, false);
        }
        target.fastUntil = SystemClock.uptimeMillis() + 1200;
        try {
            scrollWorker.execute(() -> {
                try {
                    JSONObject answer = new JSONObject(RemoteNative.scrollLocal(target.remoteHandle, lines,
                            target.generation, target.title, target.status, shortcutJson));
                    if (answer.optBoolean("at_edge", false)) {
                        prefetchHistory(target);
                    } else if (answer.optLong("offset", 0) == 0 && target.renderedScroll != 0) {
                        sendScroll(target, 0);
                        lastFollowSent = true;
                        nativeSetFollow(target.generation, true);
                    }
                } catch (Exception | LinkageError ignored) {
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            return false;
        }
        return true;
    }

    /**
     * Asks the daemon for one more row above what it has already drawn. Frames
     * arrive one per request, so this walks backwards one row at a time and the
     * buffer stays contiguous.
     */
    private void prefetchHistory(Connection target) {
        // Keep a window of rows in flight so the buffer runs ahead of the finger;
        // the daemon answers each request with one frame at that offset.
        if (target.desiredHistory < target.renderedScroll) target.desiredHistory = target.renderedScroll;
        long inFlight = target.desiredHistory - target.renderedScroll;
        while (inFlight < PREFETCH_WINDOW && target.desiredHistory < target.scrollMax) {
            target.desiredHistory++;
            sendScroll(target, target.desiredHistory);
            inFlight++;
        }
    }

    /** Asks the daemon to render at `offset`, which backfills the local buffer. */
    private void sendScroll(Connection target, long offset) {
        try {
            scrollWorker.execute(() -> {
                try { RemoteNative.scrollTo(target.remoteHandle, offset); }
                catch (RuntimeException | LinkageError ignored) { }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) { }
    }

    /** Nothing pends: local scrolling never waits on a round trip. */
    private void flushScroll() { }

    private void jumpToLive() {
        final Connection target;
        synchronized (stateLock) { target = connection; }
        if (target == null || target.isClosed() || target.remoteHandle <= 0) return;
        desiredScroll = 0;
        lastSentScroll = -1;
        scrollSeeking = true;
        if (!lastFollowSent) {
            lastFollowSent = true;
            nativeSetFollow(target.generation, true);
        }
        try {
            worker.execute(() -> {
                try { RemoteNative.scrollTo(target.remoteHandle, 0); }
                catch (RuntimeException | LinkageError ignored) { }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) { }
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
        // Zero hands type sizing to the renderer, which fits the pane's own
        // wrapping width; a first run on a fold should not be caption-sized.
        float chosenFont = displayOptions.fontSize == 0
                ? 0f
                : displayOptions.effectiveFont(compactDisplay,
                        getResources().getConfiguration().fontScale);
        nativeResetPan();
        nativeSetDisplayPreferences(chosenFont, compactDisplay,
                getResources().getConfiguration().fontScale);
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
        if (terminalRoot != null) terminalRoot.post(() -> {
            applySidebarDefault();
            applyDisplayOptions();
        });
    }

    private TextView optionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        // Caption weight: the spinner below carries the actual value.
        label.setTextSize(13);
        label.setTextColor(0xff9fb0c6);
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
        // One line of guidance; the host and status it used to repeat are already
        // in the header this sheet opens over.
        TextView help = optionLabel("Compact keeps more rows visible. Opening this sheet cancels an unsent draft.");
        options.addView(help);
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
            // Host switching needs the saved hosts before the first rail paint.
            worker.execute(() -> {
                try {
                    java.util.List<SavedRemoteHost> hosts = new RemoteHostStore(this).read();
                    runOnUiThread(() -> { savedHosts = hosts; refreshSidebar(true); });
                } catch (Exception ignored) {
                    // An unreadable store leaves the rail without host rows.
                }
            });
                    applyDisplayOptions();
                }).create();
        showLocalDialog(displayDialog);
    }


    /** Same opt-in the activity used at create time. */
    private boolean capturableSession() {
        return (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && "1".equals(android.provider.Settings.Global.getString(
                        getContentResolver(), "herdr_probe"));
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
        // Dialogs carry the same private content as the terminal, so they stay
        // secure unless the same debuggable opt-in applies; otherwise no sheet in
        // this app could ever be captured or reviewed.
        if (!capturableSession()) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
    }

    /** Mirrors every saved host's workspace tree without disturbing the terminal. */
    private void startHostSampling() {
        if (sampler == null) {
            sampler = java.util.concurrent.Executors.newScheduledThreadPool(3, runnable -> {
                Thread thread = new Thread(runnable, "herdr-host-sample");
                thread.setDaemon(true);
                return thread;
            });
        }
        for (SavedRemoteHost host : savedHosts) {
            final String hostId = host.id;
            // Hosts loaded after the sampler started still need a mirror; the
            // old early return left them showing "no session" forever.
            if (hostSamples.putIfAbsent(hostId, new HostSample()) != null) continue;
            sampler.scheduleWithFixedDelay(() -> sampleHost(hostId), 0, 3, TimeUnit.SECONDS);
        }
    }

    private void stopHostSampling() {
        if (sampler != null) {
            sampler.shutdownNow();
            sampler = null;
        }
        for (HostSample sample : hostSamples.values()) {
            closeSample(sample);
        }
        hostSamples.clear();
    }

    /** One sampler tick: keep a host's tree fresh, and idle while it is the terminal. */
    private void sampleHost(String hostId) {
        if (destroyed || hostId.equals(currentHostId) || hostId.equals(switchingTo)) {
            HostSample live = hostSamples.get(hostId);
            if (live != null) closeSample(live);
            return;
        }
        SavedRemoteHost host = null;
        for (SavedRemoteHost candidate : savedHosts) {
            if (candidate.id.equals(hostId)) host = candidate;
        }
        if (host == null || host.expectedFingerprint.isEmpty()) return;
        HostSample sample = hostSamples.get(hostId);
        if (sample == null) return;
        long now = SystemClock.uptimeMillis();
        if (sample.handle == 0) {
            if (now < sample.nextAttemptAt) return;
            try {
                RemoteNative.ensureLoaded(this);
                char[] key = new RemoteIdentityStore(this).read();
                String credential;
                try {
                    credential = new JSONObject().put("private_key", new String(key)).toString();
                } finally {
                    java.util.Arrays.fill(key, '\0');
                }
                long handle = RemoteNative.connectGrid(host.connectionJson(), credential, terminalGrid());
                if (handle <= 0) throw new IllegalStateException("connect refused");
                sample.handle = handle;
                sample.connected = false;
            } catch (Exception | LinkageError failure) {
                sample.nextAttemptAt = now + 30_000;
                return;
            }
        }
        try {
            JSONObject state = new JSONObject(RemoteNative.poll(sample.handle, sample.revision, true));
            if (state.optBoolean("unchanged", false)) {
                if (sample.connected) sample.connected = true;
                return;
            }
            if ("closed".equals(state.optString("state"))) throw new IllegalStateException("closed");
            JSONArray panes = state.optJSONArray("panes");
            if (panes != null && panes.length() > 0) {
                sample.panes = panes;
                sample.workspaces = state.optJSONArray("workspaces");
                sample.tabs = state.optJSONArray("tabs");
                sample.revision = state.optLong("token", state.optLong("revision", 0));
                sample.connected = true;
                sidebarSignature = "";
                if (!destroyed) runOnUiThread(() -> refreshSidebar(false));
            }
        } catch (Exception | LinkageError failure) {
            closeSample(sample);
            sample.nextAttemptAt = now + 30_000;
            if (!destroyed) runOnUiThread(() -> refreshSidebar(true));
        }
    }

    private void closeSample(HostSample sample) {
        long handle = sample.handle;
        sample.handle = 0;
        sample.connected = false;
        if (handle != 0) {
            try { RemoteNative.close(handle); } catch (RuntimeException | LinkageError ignored) { }
        }
    }

    private void rememberHostPane(String paneId) {
        rememberHostPane(currentHostId, paneId);
    }

    private void rememberHostPane(String hostId, String paneId) {
        if (hostId == null || paneId == null || paneId.isEmpty()) return;
        getSharedPreferences("terminal-host-panes", MODE_PRIVATE).edit()
                .putString(hostId, paneId).apply();
        String preference = "remote-startup";
        getSharedPreferences(preference, MODE_PRIVATE).edit()
                .putString("last_host", hostId)
                .putString("last_pane", paneId)
                .apply();
    }

    private String rememberedHostPane(String hostId) {
        if (hostId == null) return "";
        return getSharedPreferences("terminal-host-panes", MODE_PRIVATE).getString(hostId, "");
    }

    /** Pane title for the header, from the last chrome the host reported. */
    private String paneTitle(JSONArray panes, String paneId) {
        if (panes == null) return "";
        for (int i = 0; i < panes.length(); i++) {
            JSONObject pane = panes.optJSONObject(i);
            if (pane != null && paneId.equals(pane.optString("id", ""))) {
                return chromeTitle(pane.optString("title", ""), paneId);
            }
        }
        return "";
    }

    private static boolean hasPane(JSONArray panes, String paneId) {
        for (int i = 0; i < panes.length(); i++) {
            JSONObject pane = panes.optJSONObject(i);
            if (pane != null && paneId.equals(pane.optString("id", ""))) return true;
        }
        return false;
    }

    /**
     * Connects to another saved host with the stored device key and keeps the
     * GPUI surface: only the connection and its generation change.
     */
    private void switchHost(SavedRemoteHost host) { switchHost(host, null); }

    private void switchHost(SavedRemoteHost host, String pane) {
        if (host == null || switchingHost || host.id.equals(currentHostId)) return;
        if (host.expectedFingerprint.isEmpty()) {
            Toast.makeText(this, "Trust " + host.name + " from Remote hosts first", Toast.LENGTH_LONG).show();
            return;
        }
        switchingHost = true;
        switchingTo = host.id;
        // The mirror for this host is released before the real session opens;
        // two connections to one host would waste the handle budget.
        HostSample target = hostSamples.get(host.id);
        if (target != null) closeSample(target);
        final String forcedPane = pane;
        final Connection previous;
        synchronized (stateLock) { previous = connection; }
        if (previous != null) {
            synchronized (stateLock) { publish(previous, "Switching to " + host.name + "…"); }
        }
        worker.execute(() -> {
            long handle = 0;
            try {
                char[] key = new RemoteIdentityStore(this).read();
                String credential;
                try {
                    credential = new JSONObject().put("private_key", new String(key)).toString();
                } finally {
                    java.util.Arrays.fill(key, '\0');
                }
                handle = RemoteNative.connectGrid(host.connectionJson(), credential, terminalGrid());
                if (handle <= 0) throw new IllegalStateException("connection refused");
                String chosen = awaitHostPane(handle, host.id, forcedPane);
                final long readyHandle = handle;
                final String readyPane = chosen;
                runOnUiThread(() -> adoptHost(host, readyHandle, readyPane));
                handle = 0;
            } catch (Exception | LinkageError failure) {
                if (handle != 0) RemoteNative.close(handle);
                runOnUiThread(() -> {
                    switchingHost = false;
                    switchingTo = null;
                    Toast.makeText(this, "Could not switch to " + host.name, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** Waits for the new session, focuses its first pane and returns that id. */
    private String awaitHostPane(long handle, String hostId, String forcedPane) throws Exception {
        long until = SystemClock.uptimeMillis() + 45_000;
        JSONObject state = null;
        while (SystemClock.uptimeMillis() < until) {
            state = new JSONObject(RemoteNative.poll(handle, 0, false));
            String phase = state.optString("state");
            if ("closed".equals(phase)) throw new IllegalStateException("closed");
            // The pane list arrives with the first snapshot; an empty array is
            // not readiness.
            JSONArray listed = state.optJSONArray("panes");
            if ("ready".equals(phase) || listed != null && listed.length() > 0) break;
            Thread.sleep(150);
        }
        JSONArray panes = state == null ? null : state.optJSONArray("panes");
        if (panes == null || panes.length() == 0) throw new IllegalStateException("no panes");
        // An explicit pick wins; then the pane this phone last used on that
        // host; the daemon's own focus is often an idle shell.
        String pane = forcedPane != null && !forcedPane.isEmpty() ? forcedPane : rememberedHostPane(hostId);
        // Then a pane that is actually doing something: an idle shell reads as
        // a blank terminal even though it is rendering.
        if (pane.isEmpty() || !hasPane(panes, pane)) {
            for (int i = 0; i < panes.length() && pane.isEmpty(); i++) {
                String status = panes.getJSONObject(i).optString("status", "");
                if (!"unknown".equals(status) && !"idle".equals(status)) {
                    pane = panes.getJSONObject(i).optString("id", "");
                }
            }
        }
        if (pane.isEmpty() || !hasPane(panes, pane)) {
            for (int i = 0; i < panes.length() && pane.isEmpty(); i++) {
                if (!"unknown".equals(panes.getJSONObject(i).optString("status", ""))) {
                    pane = panes.getJSONObject(i).optString("id", "");
                }
            }
        }
        if (pane.isEmpty() || !hasPane(panes, pane)) {
            pane = state.optString("focused_pane", "");
        }
        if (pane.isEmpty() || !hasPane(panes, pane)) pane = panes.getJSONObject(0).optString("id", "");
        rememberHostPane(hostId, pane);
        if (pane.isEmpty()) throw new IllegalStateException("no pane id");
        RemoteNative.focus(handle, pane);
        until = SystemClock.uptimeMillis() + 20_000;
        while (SystemClock.uptimeMillis() < until) {
            state = new JSONObject(RemoteNative.poll(handle, 0, false));
            if (pane.equals(state.optString("pane_id"))
                    && "ready".equals(state.optString("state"))) return pane;
            Thread.sleep(150);
        }
        return pane;
    }

    private void adoptHost(SavedRemoteHost host, long handle, String pane) {
        if (destroyed || isFinishing()) {
            RemoteNative.close(handle);
            return;
        }
        Connection next;
        synchronized (stateLock) {
            Connection previous = connection;
            connection = null;
            if (previous != null) previous.close();
            next = new Connection(GENERATIONS.incrementAndGet(), handle);
            next.paneId = pane;
            next.hostName = host.name;
            scrollSeeking = false;
            lastFollowSent = true;
            next.title = host.name;
            next.remoteBoot = null;
            connection = next;
            currentHostId = host.id;
            lastSnapshotTitle = lastSnapshotText = lastSnapshotStatus = lastSnapshotShortcuts = lastSnapshotSurface = null;
            nativeSetActive(next.generation, false);
            nativeResetPan();
            publish(next, "Connecting to " + host.name + "…");
            next.poll = worker.scheduleWithFixedDelay(() -> pump(next), 0, COMMAND_POLL_MS, TimeUnit.MILLISECONDS);
        }
        if (commandPalette != null) commandPalette.close();
        commandPalette = new QuickCommandPalette(this, host, host.name, paletteOwner);
        switchingHost = false;
        switchingTo = null;
        sidebarSignature = "";
        refreshSidebar(true);
    }

    /**
     * The rail opens by default only where it leaves a usable terminal: the
     * inner panel (798 dp here) has room, the cover panel (about 494 dp) does not,
     * and opening it there would squeeze the terminal to two thirds of a phone.
     * An explicit choice always wins.
     */
    private void applySidebarDefault() {
        // The stored value is the preference for a panel with room. Folding to the
        // cover must not keep half the terminal covered by the rail, and unfolding
        // restores what the user asked for.
        int widthDp = getResources().getConfiguration().screenWidthDp;
        boolean wantsRail = getSharedPreferences("terminal-display", MODE_PRIVATE)
                .getBoolean("list", true);
        boolean fits = widthDp >= 600;
        if (sidebarOpen != (wantsRail && fits)) sidebarSignature = null;
        sidebarOpen = wantsRail && fits;
        applySidebarWidth();
    }

    private static SurfaceView findSurface(View view) {
        if (view instanceof SurfaceView) return (SurfaceView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                SurfaceView found = findSurface(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void captureScreen() {
        View decor = getWindow().getDecorView();
        final android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(
                decor.getWidth(), decor.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        // A window PixelCopy shows exactly what the compositor presents, which a
        // software draw of the hierarchy does not reproduce for scrolled content.
        android.view.PixelCopy.request(getWindow(), image, result -> {
            if (result != android.view.PixelCopy.SUCCESS) {
                decor.draw(new android.graphics.Canvas(image));
                Toast.makeText(this, "Window copy failed: " + result, Toast.LENGTH_SHORT).show();
            }
            saveScreenshot(image);
        }, new Handler(Looper.getMainLooper()));
    }

    /** Writes the composite off the UI thread; the file stays app-private. */
    private void saveScreenshot(android.graphics.Bitmap image) {
        Thread writer = new Thread(() -> {
            try (java.io.FileOutputStream out =
                         new java.io.FileOutputStream(new java.io.File(getFilesDir(), "screen.png"))) {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception ignored) {
            } finally {
                image.recycle();
            }
        }, "herdr-screenshot");
        writer.setDaemon(true);
        writer.start();
        Toast.makeText(this, "Screenshot saved", Toast.LENGTH_SHORT).show();
    }

    private void hideSidebar() {
        if (!sidebarOpen) return;
        setSidebarOpen(false);
    }

    private void showSidebar() {
        if (sidebarOpen) return;
        setSidebarOpen(true);
    }

    private void toggleSidebar() {
        setSidebarOpen(!sidebarOpen);
    }

    private void setSidebarOpen(boolean open) {
        sidebarOpen = open;
        getSharedPreferences("terminal-display", MODE_PRIVATE).edit()
                .putBoolean("list", sidebarOpen).apply();
        applySidebarWidth();
        if (sidebarOpen) {
            startHostSampling();
            refreshSidebar(true);
        } else {
            // A hidden rail consumes nothing, so close its mirror sessions.
            stopHostSampling();
        }
    }

    private void applySidebarWidth() {
        if (sidebar == null) return;
        int width = sidebarOpen ? Math.min(dp(240), Math.max(dp(140),
                terminalRoot == null ? dp(180) : terminalRoot.getWidth() / 3)) : 0;
        LinearLayout.LayoutParams layout = (LinearLayout.LayoutParams) sidebar.getLayoutParams();
        if (layout != null && layout.width != width) {
            layout.width = width;
            sidebar.setLayoutParams(layout);
        }
        if (sidebarButton != null) {
            // A closed rail must announce itself: "List" hid the hosts behind a
            // word that reads like just another terminal control.
            sidebarButton.setText(sidebarOpen ? "Hide" : "Hosts");
            sidebarButton.setSelected(sidebarOpen);
            sidebarButton.setTextColor(sidebarOpen ? 0xffe7eaf0 : 0xff76d3b2);
            sidebarButton.setContentDescription(sidebarOpen
                    ? "Hide the host and workspace sidebar"
                    : "Show the host and workspace sidebar");
        }
    }

    /** Rebuilds the rail only when the daemon's chrome actually changed. */
    private void refreshSidebar(boolean force) {
        if (!sidebarOpen || sidebarRows == null) return;
        Connection current;
        synchronized (stateLock) { current = connection; }
        StringBuilder signature = new StringBuilder();
        for (SavedRemoteHost host : savedHosts) {
            HostSample sample = hostSamples.get(host.id);
            signature.append(host.id).append(':');
            if (host.id.equals(currentHostId)) {
                signature.append("live:").append(current == null ? 0 : current.remoteRevision);
            } else if (sample == null) {
                signature.append("none");
            } else if (!sample.connected) {
                signature.append("down");
            } else {
                // The daemon's revision moves whenever the tree does, so the rail
                // never has to stringify both arrays to notice a change.
                signature.append('r').append(sample.revision);
            }
            signature.append(';');
        }
        if (current != null) signature.append(current.paneId);
        signature.append(currentHostId);
        if (!force && signature.toString().equals(sidebarSignature)) return;
        sidebarSignature = signature.toString();
        if (savedHosts.isEmpty()) {
            pruneRailRows(java.util.Collections.emptySet());
            sidebarRows.removeAllViews();
            sidebarRows.addView(railText("No saved hosts yet", 13, 0xff6f7d90));
            return;
        }
        // Rows are reused, never rebuilt: the sampler refreshes every three
        // seconds and replacing a view under a finger cancels its tap.
        java.util.List<View> order = new java.util.ArrayList<>();
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (int h = 0; h < savedHosts.size(); h++) {
            SavedRemoteHost host = savedHosts.get(h);
            boolean live = host.id.equals(currentHostId);
            HostSample sample = hostSamples.get(host.id);
            JSONArray workspaces = live && current != null ? current.workspaces
                    : sample == null ? null : sample.workspaces;
            JSONArray panes = live && current != null ? current.panes
                    : sample == null ? null : sample.panes;
            addHostRows(host, live, sample, workspaces, panes, current, order, keep);
            if (h + 1 < savedHosts.size()) order.add(railRule(host.id, keep));
        }
        pruneRailRows(keep);
        commitRailOrder(order);
    }

    private void addHostRows(SavedRemoteHost host, boolean live, HostSample sample, JSONArray workspaces,
            JSONArray panes, Connection current, java.util.List<View> order, java.util.Set<String> keep) {
        String hostKey = "host:" + host.id;
        keep.add(hostKey);
        RailHostRow header = railHostRows.get(hostKey);
        if (header == null) {
            header = createRailHostRow(host);
            railHostRows.put(hostKey, header);
        }
        updateRailHostRow(header, host, live, sample, workspaces);
        order.add(header.row);
        if (workspaces == null || panes == null) {
            String noteKey = "note:" + host.id;
            keep.add(noteKey);
            TextView note = railNotes.get(noteKey);
            if (note == null) {
                note = new TextView(this);
                note.setTextSize(11);
                note.setTextColor(0xff66748a);
                note.setPadding(dp(10), dp(2), dp(6), dp(6));
                note.setTypeface(android.graphics.Typeface.create("sans-serif-italic",
                        android.graphics.Typeface.NORMAL));
                railNotes.put(noteKey, note);
            }
            String text = sample != null && !sample.connected ? "connecting…" : "no session";
            if (!text.contentEquals(note.getText())) note.setText(text);
            order.add(note);
            return;
        }
        for (int w = 0; w < workspaces.length(); w++) {
            JSONObject workspace = workspaces.optJSONObject(w);
            if (workspace == null) continue;
            String workspaceId = workspace.optString("id", "");
            String workspaceLabel = chromeTitle(workspace.optString("label", ""), workspaceId);
            String workspaceKey = "ws:" + host.id + "/" + workspaceId;
            keep.add(workspaceKey);
            RailWorkspaceRow workspaceRow = railWorkspaces.get(workspaceKey);
            if (workspaceRow == null) {
                workspaceRow = createRailWorkspaceRow();
                railWorkspaces.put(workspaceKey, workspaceRow);
            }
            // Orientation on a long rail: mark the workspace that holds the
            // focused pane, not just the pane itself.
            boolean holdsSelection = false;
            if (live && current != null) {
                for (int q = 0; q < panes.length(); q++) {
                    JSONObject pane = panes.optJSONObject(q);
                    if (pane == null || !workspaceId.equals(pane.optString("workspace_id", ""))) continue;
                    if (current.paneId.equals(pane.optString("id", ""))) { holdsSelection = true; break; }
                }
            }
            updateRailWorkspaceRow(workspaceRow, workspaceLabel, live, current, workspaceId,
                    holdsSelection);
            order.add(workspaceRow.view);
            for (int p = 0; p < panes.length(); p++) {
                JSONObject pane = panes.optJSONObject(p);
                if (pane == null || !workspaceId.equals(pane.optString("workspace_id", ""))) continue;
                String paneId = pane.optString("id", "");
                String title = chromeTitle(pane.optString("title", ""), paneId);
                boolean selected = live && current != null && paneId.equals(current.paneId);
                String paneKey = "pane:" + host.id + "/" + paneId;
                keep.add(paneKey);
                RailPaneRow row = railPanes.get(paneKey);
                if (row == null) {
                    row = createRailPaneRow();
                    railPanes.put(paneKey, row);
                }
                updateRailPaneRow(row, host, live, current, paneId, title,
                        pane.optString("status", "unknown"), selected);
                order.add(row.row);
            }
        }
    }

    /** Moves reused rows into their new positions without touching rows that already sit there. */
    private void commitRailOrder(java.util.List<View> order) {
        for (int i = 0; i < order.size(); i++) {
            View view = order.get(i);
            if (view.getParent() != sidebarRows) {
                sidebarRows.addView(view, Math.min(i, sidebarRows.getChildCount()));
            } else if (sidebarRows.indexOfChild(view) != i) {
                sidebarRows.removeView(view);
                sidebarRows.addView(view, i);
            }
        }
        while (sidebarRows.getChildCount() > order.size()) {
            sidebarRows.removeViewAt(order.size());
        }
    }

    private void pruneRailRows(java.util.Set<String> keep) {
        prune(railHostRows, keep, holder -> holder.row);
        prune(railWorkspaces, keep, holder -> holder.view);
        prune(railPanes, keep, holder -> holder.row);
        prune(railNotes, keep, view -> view);
        prune(railRules, keep, view -> view);
    }

    private <T> void prune(java.util.Map<String, T> rows, java.util.Set<String> keep, java.util.function.Function<T, View> view) {
        java.util.Iterator<java.util.Map.Entry<String, T>> iterator = rows.entrySet().iterator();
        while (iterator.hasNext()) {
            java.util.Map.Entry<String, T> entry = iterator.next();
            if (keep.contains(entry.getKey())) continue;
            View stale = view.apply(entry.getValue());
            if (stale.getParent() == sidebarRows) sidebarRows.removeView(stale);
            iterator.remove();
        }
    }

    private RailHostRow createRailHostRow(SavedRemoteHost host) {
        RailHostRow holder = new RailHostRow();
        holder.row = new LinearLayout(this);
        holder.row.setOrientation(LinearLayout.HORIZONTAL);
        holder.row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        holder.row.setMinimumHeight(dp(48));
        holder.row.setPadding(dp(6), dp(8), dp(6), dp(8));
        holder.row.setSaveFromParentEnabled(false);
        holder.dot = new View(this);
        holder.dot.setBackground(roundedDrawable(0xff8892a0, 4));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.setMarginEnd(dp(8));
        holder.row.addView(holder.dot, dotParams);
        holder.name = railText(host.name, 13, 0xffd7dee8);
        holder.name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
        nameParams.leftMargin = dp(1);
        holder.row.addView(holder.name, nameParams);
        holder.state = railText("", 10, 0xff8c9bb0);
        holder.state.setSingleLine(true);
        holder.state.setGravity(android.view.Gravity.END);
        LinearLayout.LayoutParams hostStateParams = new LinearLayout.LayoutParams(-2, -2);
        hostStateParams.leftMargin = dp(6);
        holder.row.addView(holder.state, hostStateParams);
        holder.host = host;
        holder.row.setContentDescription("Connect to " + host.name);
        holder.row.setOnClickListener(view -> {
            switchHost(holder.host);
        });
        return holder;
    }

    private void updateRailHostRow(RailHostRow holder, SavedRemoteHost host, boolean live,
            HostSample sample, JSONArray workspaces) {
        holder.host = host;
        // Connection state is the thing a glance should answer, so it gets its
        // own dot shape: solid when connected, ring while dialling, hollow when down.
        String status = live || (sample != null && sample.connected) ? "connected"
                : sample != null ? "connecting" : "offline";
        if (!status.equals(holder.status)) {
            boolean pulse = holder.status != null;
            holder.status = status;
            // Colour alone cannot say it, so the dot is paired with its word.
            String word = "connected".equals(status) ? "live"
                    : "connecting".equals(status) ? "dialling" : "";
            holder.state.setText(word);
            holder.hostState = word;
            holder.row.setContentDescription(hostDescription(holder));
            holder.dot.setBackground("connected".equals(status)
                    ? roundedDrawable(0xff76d3b2, 4)
                    : ringDrawable("connecting".equals(status) ? 0xfff0b778 : 0xff5c6779));
            if (pulse) pulseRailDot(holder.dot);
        }
        if (holder.live != live) {
            holder.live = live;
            holder.row.setBackground(live ? roundedDrawable(0x1476d3b2, 9) : null);
            holder.name.setTextColor(live ? 0xff76d3b2 : 0xffd7dee8);
        }
        if (!host.name.contentEquals(holder.name.getText())) holder.name.setText(host.name);
        int count = workspaces == null ? 0 : workspaces.length();
        if (count != holder.counted) {
            // Workspaces are enumerated below; keep the count for screen readers.
            holder.counted = count;
            holder.row.setContentDescription(hostDescription(holder));
        }
    }

    private RailWorkspaceRow createRailWorkspaceRow() {
        RailWorkspaceRow holder = new RailWorkspaceRow();
        // The desktop lists workspaces in sentence case with the pane title below,
        // not as shouted section headings; match that reading order.
        TextView row = railText("", 12, 0xffc2ccda);
        row.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(10), dp(13), dp(6), dp(7));
        row.setOnClickListener(view -> {
            if (holder.live) focusChrome(holder.connection, "workspace", holder.workspaceId);
        });
        holder.view = row;
        return holder;
    }

    private void updateRailWorkspaceRow(RailWorkspaceRow holder, String label, boolean live,
            Connection connection, String workspaceId, boolean holdsSelection) {
        holder.live = live;
        holder.connection = connection;
        holder.workspaceId = workspaceId;
        TextView row = holder.view;
        if (!label.contentEquals(row.getText())) {
            row.setText(label);
            row.setContentDescription("Focus workspace " + label);
        }
        int color = holdsSelection ? 0xff76d3b2 : live ? 0xffc2ccda : 0xff8c9bb0;
        if (row.getCurrentTextColor() != color) row.setTextColor(color);
    }

    private RailPaneRow createRailPaneRow() {
        RailPaneRow holder = new RailPaneRow();
        holder.row = new LinearLayout(this);
        holder.row.setOrientation(LinearLayout.HORIZONTAL);
        holder.row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        holder.row.setMinimumHeight(dp(48));
        holder.row.setPadding(dp(10), dp(6), dp(6), dp(6));
        holder.row.setSaveFromParentEnabled(false);
        holder.dot = new View(this);
        holder.dot.setBackground(roundedDrawable(0xff8892a0, 4));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.setMarginEnd(dp(8));
        holder.row.addView(holder.dot, dotParams);
        holder.title = railText("", 12, 0xffc2ccda);
        holder.title.setSingleLine(false);
        holder.title.setMaxLines(2);
        holder.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        holder.title.setLineSpacing(0, 1.05f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.leftMargin = dp(1);
        holder.row.addView(holder.title, params);
        // A colour alone cannot say what an agent is doing; name the state.
        holder.state = railText("", 10, 0xff8c9bb0);
        holder.state.setSingleLine(true);
        holder.state.setEllipsize(android.text.TextUtils.TruncateAt.END);
        holder.state.setGravity(android.view.Gravity.END);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(-2, -2);
        stateParams.leftMargin = dp(6);
        holder.row.addView(holder.state, stateParams);
        holder.row.setOnClickListener(view -> {
            if (holder.live) {
                focusRemotePane(holder.connection, holder.paneId);
            } else {
                switchHost(holder.host, holder.paneId);
            }
        });
        return holder;
    }

    private void updateRailPaneRow(RailPaneRow holder, SavedRemoteHost host, boolean live,
            Connection connection, String paneId, String title, String status, boolean selected) {
        holder.host = host;
        holder.live = live;
        holder.connection = connection;
        holder.paneId = paneId;
        if (!status.equals(holder.status)) {
            boolean pulse = holder.status != null;
            holder.status = status;
            holder.dot.setBackground(roundedDrawable(0xff000000 | railStatusColor(status), 4));
            if (pulse) {
                pulseRailDot(holder.dot);
                            }
        }
        if (holder.selected != selected) {
            holder.selected = selected;
            holder.row.setBackground(selected ? roundedDrawable(0x3076d3b2, 8) : null);
            holder.title.setTextColor(selected ? 0xfff2f6fb : 0xffc2ccda);
        }
        if (!title.contentEquals(holder.title.getText())) {
            holder.title.setText(title);
            holder.row.setContentDescription("Focus pane " + title);
        }
        String state = status == null || status.isEmpty() || "unknown".equals(status)
                ? "" : status;
        if (!state.contentEquals(holder.state.getText())) holder.state.setText(state);
        // A row description overrides its children, so the state has to be said here.
        String described = state.isEmpty() ? "Focus pane " + title
                : "Focus pane " + title + ", " + state;
        if (!described.contentEquals(holder.row.getContentDescription() == null
                ? "" : holder.row.getContentDescription())) {
            holder.row.setContentDescription(described);
        }
    }

    private View railRule(String hostId, java.util.Set<String> keep) {
        String key = "rule:" + hostId;
        keep.add(key);
        View rule = railRules.get(key);
        if (rule != null) return rule;
        rule = new View(this);
        rule.setBackgroundColor(0xff1c222b);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.topMargin = dp(12);
        params.bottomMargin = dp(6);
        params.leftMargin = dp(6);
        params.rightMargin = dp(6);
        rule.setLayoutParams(params);
        railRules.put(key, rule);
        return rule;
    }

    private static final class RailHostRow {
        LinearLayout row;
        View dot;
        TextView name;
        TextView state;
        String hostState = "";
        SavedRemoteHost host;
        String status;
        boolean live;
        int counted = -1;
    }

    private static final class RailWorkspaceRow {
        TextView view;
        Connection connection;
        String workspaceId = "";
        boolean live;
    }

    private static final class RailPaneRow {
        LinearLayout row;
        View dot;
        TextView title;
        TextView state;
        SavedRemoteHost host;
        Connection connection;
        String paneId = "";
        boolean live;
        boolean selected;
        String status;
    }

    private static int railStatusColor(String status) {
        if ("working".equals(status)) return 0xf0b778;
        if ("blocked".equals(status)) return 0xff6b6b;
        if ("done".equals(status)) return 0x6ab0f3;
        if ("idle".equals(status)) return 0x76d3b2;
        return 0x8892a0;
    }

    private android.graphics.drawable.GradientDrawable ringDrawable(int color) {
        android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setColor(0x00000000);
        ring.setStroke(dp(2), color);
        return ring;
    }

    /** A single scale pulse: enough to catch the eye, short enough to stay quiet. */
    private void pulseRailDot(View dot) {
        dot.animate().cancel();
        dot.setScaleX(1f);
        dot.setScaleY(1f);
        dot.animate().scaleX(1.8f).scaleY(1.8f).setDuration(140)
                .withEndAction(() -> dot.animate().scaleX(1f).scaleY(1f).setDuration(180).start())
                .start();
    }

    private TextView railText(String text, int size, int color) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setSaveEnabled(false);
        return label;
    }

    private android.graphics.drawable.GradientDrawable roundedDrawable(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

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
                    // 48 dp is Android's minimum touch target; 40 was below it.
                    row.setMinimumHeight(dp(48));
                    row.setPadding(dp(16), 0, dp(8), 0);
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
                .setTitle("Panes")
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
        row.setPadding(dp(32), 0, dp(8), 0);
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
            rememberHostPane(paneId);
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
        // Rail colors are sized without an alpha channel; a raw int would be
        // fully transparent.
        dot.setBackground(roundedDrawable(0xff000000 | color, 4));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(dp(8), dp(8));
        layout.setMarginEnd(dp(8));
        dot.setLayoutParams(layout);
        return dot;
    }

    private TextView chromeLabel(String text, int size, int color, boolean header) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(0xff000000 | color);
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
        int masked = event.getActionMasked();
        if (masked == MotionEvent.ACTION_DOWN) {
            stopFling();
            touchDownX = edgeStartX = event.getX();
            edgeClaimed = false;
            touchDownY = lastTouchY = event.getY();
            lastMoveAt = SystemClock.uptimeMillis();
            scrollVelocity = 0f;
            scrolling = false;
            scrollFraction = 0f;
        } else if (masked == MotionEvent.ACTION_MOVE && count == 1) {
            // The system owns the screen edges for Back, so the open gesture starts
            // inside the left third rather than at the very edge.
            int width = terminalSurface == null ? 0 : terminalSurface.getWidth();
            if (!edgeClaimed && !sidebarOpen && width > 0 && touchDownX < width / 2f) {
                float dx = event.getX() - edgeStartX;
                float dy = Math.abs(event.getY() - touchDownY);
                if (dx > dp(60) && dx > dy * 1.5f) {
                    edgeClaimed = true;
                    showSidebar();
                    return true;
                }
            }
            if (edgeClaimed) return true;
            if (!scrolling && Math.abs(event.getY() - touchDownY) > dp(8)) scrolling = true;
            if (scrolling) {
                long now = SystemClock.uptimeMillis();
                float dy = event.getY() - lastTouchY;
                float dt = Math.max(1, now - lastMoveAt);
                // Smoothed drag velocity in pixels per millisecond.
                scrollVelocity = 0.6f * scrollVelocity + 0.4f * (dy / dt);
                lastMoveAt = now;
                float rowPx = rowHeightPx();
                scrollFraction += dy / rowPx;
                lastTouchY = event.getY();
                int lines = (int) scrollFraction;
                if (lines != 0) {
                    scrollFraction -= lines;
                    requestScroll(lines);
                }
                return true;
            }
        } else if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
            if (edgeClaimed) {
                edgeClaimed = false;
                return true;
            }
            if (scrolling) {
                scrolling = false;
                flushScroll();
                if (masked == MotionEvent.ACTION_UP && Math.abs(scrollVelocity) > 0.12f) {
                    flinging = true;
                    lastMoveAt = SystemClock.uptimeMillis();
                    flingRemainder = 0f;
                    if (terminalSurface != null) terminalSurface.postOnAnimation(flingStep);
                }
                return true;
            }
            stopFling();
        }
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
        startHostSampling();
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
        // Mirrors are only useful on screen; three SSH sessions polling in the
        // background would spend battery for a rail nobody can see.
        stopHostSampling();
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
        stopHostSampling();
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

    /** Desktop-style session control for the focused tab and pane. */
    private void showSessionActions() {
        final Connection target;
        synchronized (stateLock) { target = connection; }
        if (target == null || target.isClosed()) return;
        if (target.remoteHandle <= 0) {
            Toast.makeText(this, "Session controls need the SSH workflow", Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = {"New tab", "Split right", "Split down", "Toggle zoom", "Close pane", "Close tab",
                "Jump to live output", "Remote hosts…", "Save screenshot"};
        String[] actions = {"tab.new", "pane.split.right", "pane.split.down", "pane.zoom.on", "pane.close", "tab.close",
                "scroll.live", "hosts.list", "capture.screen"};
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        AlertDialog sheet = new AlertDialog.Builder(this)
                .setTitle("Session")
                .setView(list)
                .setNegativeButton("Cancel", null)
                .create();
        for (int i = 0; i < labels.length; i++) {
            // Group create/layout from view/utilities, and mark the closes as
            // destructive instead of styling them like "New tab".
            if (i == 4 || i == 6) list.addView(sheetDivider());
            boolean destructive = actions[i].endsWith(".close");
            TextView row = new TextView(this);
            row.setText(labels[i]);
            row.setTextSize(16);
            row.setTextColor(destructive ? 0xffe0a0a0 : 0xffdce5ef);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(48));
            row.setPadding(dp(20), 0, dp(20), 0);
            row.setSaveEnabled(false);
            final int index = i;
            row.setOnClickListener(view -> {
                sheet.dismiss();
                runSessionChoice(target, labels[index], actions[index]);
            });
            list.addView(row);
        }
        showLocalDialog(sheet);
    }

    private View sheetDivider() {
        View line = new View(this);
        line.setBackgroundColor(0x223342);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.setMargins(dp(20), dp(4), dp(20), dp(4));
        line.setLayoutParams(params);
        return line;
    }

    private void runSessionChoice(Connection target, String label, String action) {
        if ("scroll.live".equals(action)) {
            jumpToLive();
            return;
        }
        if ("capture.screen".equals(action)) {
            captureScreen();
            return;
        }
        if ("hosts.list".equals(action)) {
            startActivity(new android.content.Intent(this, RemoteHostsActivity.class)
                    .putExtra("skip_reopen", true));
            return;
        }
        if ("pane.close".equals(action) || "tab.close".equals(action)) {
            confirmSessionAction(target, action, label);
        } else if ("pane.zoom.on".equals(action)) {
            // One control toggles: the daemon reports the current zoom state.
            runSessionAction(target, zoomed(target) ? "pane.zoom.off" : "pane.zoom.on", "Zoom");
        } else {
            runSessionAction(target, action, label);
        }
    }

    private boolean zoomed(Connection target) {
        JSONArray tabs = target.tabs;
        if (tabs == null) return false;
        for (int i = 0; i < tabs.length(); i++) {
            JSONObject tab = tabs.optJSONObject(i);
            if (tab != null && tab.optBoolean("focused") && tab.optBoolean("zoomed")) return true;
        }
        return false;
    }

    private void confirmSessionAction(Connection target, String action, String label) {
        new AlertDialog.Builder(this)
                .setTitle(label + "?")
                .setMessage("pane.close".equals(action)
                        ? "Closing the pane terminates its processes. This cannot be undone."
                        : "Closing the tab terminates its panes and processes. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(label, (dialog, which) -> runSessionAction(target, action, label))
                .show();
    }

    private void runSessionAction(Connection target, String action, String label) {
        try {
            JSONObject result = new JSONObject(RemoteNative.sessionAction(target.remoteHandle, action));
            if (!result.optBoolean("queued", false)) throw new IllegalStateException("not queued");
            synchronized (stateLock) {
                if (current(target)) publish(target, label + "…");
            }
        } catch (Exception | LinkageError failure) {
            Toast.makeText(this, "Could not apply " + label + "; reopen and check the session", Toast.LENGTH_LONG).show();
        }
    }

    /** A terminal bell: a short vibration, never a sound that overrides the ring mode. */
    private void ringBell(int count) {
        if (destroyed || isFinishing()) return;
        android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            long millis = Math.min(600L, 40L * Math.max(1, count));
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(millis,
                    android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (RuntimeException ignored) {
            // A device policy may refuse vibration; the terminal stays usable.
        }
    }

    /** Agent alerts from the daemon, posted on one bounded channel. */
    private void postNotice(String title, String body, String paneId) {
        if (destroyed || isFinishing()) return;
        android.app.NotificationManager manager =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        android.app.NotificationChannel channel = new android.app.NotificationChannel(
                "herdr-agent-alerts", "Agent alerts", android.app.NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("A Herdr agent is waiting on you");
        manager.createNotificationChannel(channel);
        android.app.Notification notification = new android.app.Notification.Builder(this, "herdr-agent-alerts")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .build();
        try {
            manager.notify(paneId.isEmpty() ? title.hashCode() : paneId.hashCode(), notification);
        } catch (RuntimeException ignored) {
            // Posting can be refused while the user restricts notifications.
        }
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
                // Keep full-speed polling briefly after typing so echo stays immediate.
                current.fastUntil = SystemClock.uptimeMillis() + 1500;
                // Binary input has no daemon receipt. Submit is one TextCommit+Enter
                // batch; this status deliberately means queued, never acknowledged.
                synchronized (stateLock) {
                    if (current(current)) publish(current, "Input queued over SSH");
                }
                mutation = false;
            }
            if (!current(current)) return;
            long nowMs = SystemClock.uptimeMillis();
            if (nowMs < current.nextRemotePollAt) return;
            // A live scroll wants frames, not just state: poll near 30 Hz until
            // the gesture settles, then fall back.
            long cadence = nowMs < current.fastUntil ? (scrollSeeking ? 33 : 50) : 100;
            current.nextRemotePollAt = nowMs + cadence;
            String rawState = RemoteNative.poll(current.remoteHandle, current.remoteRevision, false);
            if (rawState == null || rawState.length() > 1024 * 1024) throw new IOException("Oversized remote state");
            JSONObject state = new JSONObject(rawState);
                        if (state.optBoolean("unchanged", false)) {
                String quietPhase = state.optString("state", "");
                String quietPane = state.optString("pane_id", "");
                boolean smallChange = !quietPhase.equals(current.remotePhase)
                        || !quietPane.equals(current.paneId)
                        || !state.optString("boot_id", "").equals(current.remoteBoot == null ? "" : current.remoteBoot);
                if (!smallChange) {
                    synchronized (stateLock) {
                        if (!current(current)) return;
                        JSONObject quietScroll = state.optJSONObject("scroll");
                        if (quietScroll != null) {
                            current.scrollOffset = quietScroll.optLong("offset", 0);
                            current.scrollMax = quietScroll.optLong("max", 0);
                            current.scrollRows = quietScroll.optLong("rows", 0);
                        }
                    }
                    return;
                }
                // The tree did not move but the connection did: fetch the full view once.
                rawState = RemoteNative.poll(current.remoteHandle, 0, false);
                state = new JSONObject(rawState);
            }
            String phase = state.getString("state");
            if ("closed".equals(phase)) {
                fail(current, GpuiTerminalProtocol.title(state.optString("status", "Remote session closed")));
                return;
            }
            // A transport that stops answering leaves the session "connecting"
            // forever; bound that state so the user gets a reason and an action.
            String health = state.optString("status", "");
            boolean degraded = phase.equals("connecting") || health.startsWith("endpoint health check");
            if (degraded) {
                if (current.degradedSince == 0) current.degradedSince = SystemClock.uptimeMillis();
                else if (SystemClock.uptimeMillis() - current.degradedSince > 15_000) {
                    fail(current, health.isEmpty() ? "The host stopped responding" : health);
                    return;
                }
            } else current.degradedSince = 0;
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
                current.remotePhase = phase;
                current.remoteRevision = state.optLong("token", state.optLong("revision", 0));
                current.text = text;
                current.ready = selected;
                current.surface = selected ? state.optString("surface", "") : "";
                current.workspaces = state.optJSONArray("workspaces");
                current.tabs = state.optJSONArray("tabs");
                current.panes = state.optJSONArray("panes");
                current.chromePane = pane;
                String paneName = paneTitle(current.panes, current.paneId);
                current.title = paneName.isEmpty()
                        ? current.hostName
                        : paneName + " · " + current.hostName;
                current.renderedScroll = state.optLong("rendered_scroll", current.renderedScroll);
                JSONObject scroll = state.optJSONObject("scroll");
                if (scroll != null) {
                    current.scrollOffset = scroll.optLong("offset", 0);
                    current.scrollMax = scroll.optLong("max", 0);
                    current.scrollRows = scroll.optLong("rows", 0);
                    if (scrollSeeking) {
                        // The daemon answers with the last offset it applied; do not
                        // drag the user back while a newer target is still in flight.
                        // Convergence is what the daemon has drawn, not what we asked for.
                        if (current.renderedScroll == desiredScroll) scrollSeeking = false;
                    } else {
                        desiredScroll = current.scrollOffset;
                        lastSentScroll = current.scrollOffset;
                    }
                }
                int bell = state.optInt("bell", 0);
                if (bell > 0) {
                    final int rings = bell;
                    main.post(() -> ringBell(rings));
                }
                JSONArray notices = state.optJSONArray("notifications");
                if (notices != null) {
                    for (int i = 0; i < Math.min(notices.length(), 8); i++) {
                        JSONObject notice = notices.optJSONObject(i);
                        if (notice == null) continue;
                        String noticeTitle = notice.optString("title", "");
                        if (noticeTitle.isEmpty()) continue;
                        String noticeBody = notice.optString("body", "");
                        String noticePane = notice.optString("pane_id", "");
                        main.post(() -> postNotice(noticeTitle, noticeBody, noticePane));
                    }
                }
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
                publish(current, selected ? "SSH connected" : "Waiting for selected remote pane…");
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
            showReconnectBanner(message);
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
        target.status = status;
        nativeSetSnapshot(target.generation, target.title, target.text, status, shortcutJson, target.surface);
        lastSnapshotGeneration = target.generation;
        lastSnapshotTitle = target.title;
        lastSnapshotText = target.text;
        lastSnapshotStatus = status;
        lastSnapshotShortcuts = shortcutJson;
        lastSnapshotSurface = target.surface;
    }

    private final class Connection {
        long degradedSince;
        final long generation;
        final CompanionClient client;
        final long remoteHandle;
        volatile boolean closed;
        String remoteBoot;
        volatile String paneId = launch.paneId;
        volatile long remoteRevision;
        volatile String remotePhase = "";
        volatile String status = "Connecting…";
        volatile long fastUntil;
        volatile long renderedScroll;
        volatile long desiredHistory;
        long nextRemotePollAt;
        volatile String title = launch.title;
        volatile String hostName = launch.name();
        String text = "";
        String surface = "";
        JSONArray workspaces, tabs, panes;
        volatile long clipboardSerial;
        volatile long scrollOffset, scrollMax, scrollRows;
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

    private static native void nativeSetDisplayPreferences(float fontSize, boolean compact,
            float systemFontScale);

    /** Moves the terminal horizontally by this many logical pixels. */
    private static native void nativePan(float delta);

    /** Drops the horizontal offset for a new pane or a new type size. */
    private static native void nativeResetPan();
    private static native void nativeSetTheme(int index);
    private static native void nativeSetSnapshot(long generation, String title, String text, String status,
            String shortcutsJson, String surfaceJson);
    private static native void nativeSetActive(long generation, boolean active);
    private static native void nativeSetFollow(long generation, boolean follow);
    private static native void nativeShowKeyboard(long generation);
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
