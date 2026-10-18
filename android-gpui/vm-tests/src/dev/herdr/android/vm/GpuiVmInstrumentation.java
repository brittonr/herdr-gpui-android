package dev.herdr.android.vm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.WindowInsets;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.Spinner;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Test APK only. The target APK is unmodified and receives no external network traffic. */
public final class GpuiVmInstrumentation extends Instrumentation {
    private String targetPackage;
    private static final String TERMINAL = "dev.herdr.android.GpuiTerminalActivity";
    private static final String LAUNCHER = "dev.herdr.android.MainActivity";
    private static final String SYNTHETIC_SCREEN = "HERDR GPUI VM FIXTURE\nSynthetic terminal, no user host\nASCII: abc XYZ 0123\nUnicode: αβ 中日 🧪\n$ printf 'runtime ready'\nruntime ready\n";
    private static final long WAIT_MS = 120000;
    private final List<String> passes = new ArrayList<>();
    private final List<String> skips = new ArrayList<>();
    private final JSONArray observations = new JSONArray();
    private final List<Fixture> fixtures = new ArrayList<>();
    private volatile Fixture pendingFixture;
    private volatile Activity terminal;
    private volatile Throwable lifecycleFailure;
    private Activity launcher;
    private File output;
    private String runId;
    private boolean layoutOnly;
    private boolean frameOnly;
    private boolean quickCommandsOnly;
    private ClassLoader targetLoader;
    private SharedPreferences displayPreferences;
    private java.util.Map<String, ?> originalDisplayPreferences;
    private Object hostStore;
    private List<?> originalHosts;
    private long lastFrameHash;
    private int lastHeaderBottom, lastTerminalInkHeight;

    @Override public void onCreate(Bundle args) {
        runId = args == null ? null : args.getString("run_id");
        layoutOnly = args != null && "true".equals(args.getString("layout_only"));
        frameOnly = args != null && "true".equals(args.getString("frame_only"));
        quickCommandsOnly = args != null && "true".equals(args.getString("quick_commands_only"));
        if (runId == null) runId = "manual-" + System.currentTimeMillis();
        require(runId.matches("[A-Za-z0-9_-]{1,80}"), "Invalid test run ID");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Throwable failure = null;
        try {
            targetLoader = getTargetContext().getClassLoader();
            targetPackage = getTargetContext().getPackageName();
            displayPreferences = getTargetContext().getSharedPreferences("terminal-display", 0);
            originalDisplayPreferences = displayPreferences.getAll();
            require(displayPreferences.edit().putInt("mode", 0).putInt("font", 0).putInt("alt", 0).commit(),
                    "Could not establish deterministic local display preferences");
            output = new File(getTargetContext().getFilesDir(), "vm-tests/" + runId);
            require(output.isDirectory() || output.mkdirs(), "Cannot create app-owned test output");
            launcher = startActivitySync(new Intent().setClassName(targetPackage, LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runSuite();
        } catch (Throwable problem) {
            failure = unwrap(problem);
            android.util.Log.e("HerdrVmTests", "VM runtime failure", failure);
            try { if (terminal != null && !terminal.isDestroyed()) capture("failure"); }
            catch (Throwable screenshotFailure) { android.util.Log.w("HerdrVmTests", "Failure screenshot unavailable", screenshotFailure); }
        } finally {
            try { if (terminal != null) onMain(() -> terminal.finish()); }
            catch (Throwable ignored) { }
            try { restoreDisplayPreferences(); }
            catch (Throwable restoreFailure) { if (failure == null) failure = restoreFailure; }
            try { restoreHosts(); }
            catch (Throwable restoreFailure) { if (failure == null) failure = restoreFailure; }
            try { writeReport(failure); }
            catch (Throwable reportFailure) { if (failure == null) failure = reportFailure; }
            result.putString("stream", "\n" + (failure == null ? "PASS" : "FAIL: " + failure)
                    + "\nPassed checks: " + passes.size() + "\nSkipped checks: " + skips
                    + "\nEvidence: " + (output == null ? "unavailable" : output.getAbsolutePath()) + "\n"
                    + "Synthetic transport only; TLS and a real Herdr server were not tested.\n");
            result.putString("result", failure == null ? "PASS" : "FAIL");
            finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
        }
    }

    @Override public void callActivityOnResume(Activity activity) {
        boolean injected = false;
        try {
            if (activity.getClass().getName().equals(TERMINAL) && !activity.isFinishing()
                    && field(activity, "launch") != null && field(activity, "connection") == null) {
                require(pendingFixture != null, "No authorized synthetic fixture for terminal resume");
                injectConnection(activity, pendingFixture);
                injected = true;
            }
            super.callActivityOnResume(activity);
            if (injected) scheduleRealPump(activity);
        } catch (Throwable problem) {
            lifecycleFailure = unwrap(problem);
            android.util.Log.e("HerdrVmTests", "Fixture installation failed", lifecycleFailure);
            activity.finish();
        }
    }

    private void runSuite() throws Exception {
        if (!frameOnly && !layoutOnly) checkQuickCommandStorage();
        Fixture healthy = new Fixture("healthy");
        open(healthy, "VM fixture · Compact layout with a deliberately long title to check truncation and status access");
        await(() -> ready(terminal), "first synthetic screen through real Activity pump");
        pass("Real GPUI Activity started; JNI library loaded; synthetic screen parsed and published");
        require(((terminal.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0),
                "Target FLAG_SECURE was unexpectedly absent");
        capture("01-connected");
        pass("PixelCopy captured a non-uniform real GPUI SurfaceView without removing FLAG_SECURE");
        checkGeometry("initial-auto", true);
        WindowInsets initialInsets = terminal.getWindow().getDecorView().getRootWindowInsets();
        require(initialInsets != null && !initialInsets.isVisible(WindowInsets.Type.ime()),
                "Soft keyboard was unexpectedly visible before an explicit Keyboard request");
        pass("Soft keyboard remains hidden on initial compact terminal launch");
        if (layoutOnly) {
            long initialHash = lastFrameHash;
            setDisplayOptions(1, 18, 0);
            captureDifferent("layout-large-font", initialHash);
            checkGeometry("compact-large-font", true);
            AlertDialog dialog = openDisplayDialog();
            require(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isShown(), "Apply button is not available at this configuration");
            onMain(() -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            require(healthy.posts().isEmpty(), "Layout-only checks unexpectedly sent terminal input");
            pass("Compact large-font profile paints without input at the supplied display/font-scale configuration");
            close();
            return;
        }
        if (frameOnly) { captureQuickCommandFrames(healthy); close(); return; }
        if (quickCommandsOnly) { close(); checkQuickCommandTransport(); checkQuickCommandUi(); return; }
        checkDisplayChanges(healthy);

        final Activity first = terminal;
        final long generation = generation(first);
        int physicalDevice = findPhysicalKeyboard();
        final int keyboard = physicalDevice;
        if (keyboard >= 0) {
            int before = healthy.posts().size();
            onMain(() -> {
                physical(first, keyboard, KeyEvent.KEYCODE_A, 0);
                physical(first, keyboard, KeyEvent.KEYCODE_ENTER, 0);
                physical(first, keyboard, KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON);
            });
            await(() -> healthy.posts().size() == before + 3, "physical handler commands");
            requirePost(healthy, before, "text", "a");
            requirePost(healthy, before + 1, "keys", "enter");
            requirePost(healthy, before + 2, "keys", "ctrl+c");
            pass("Physical-device KeyEvent handler → JNI → native FIFO → client preserves text/Enter/Ctrl-C order");
            checkPhysicalCompactInput(first, healthy, keyboard);
            checkDialogIsolation(first, healthy, keyboard, null);
        } else {
            skips.add("Physical keyboard handler: Android exposed no non-virtual keyboard device");
        }

        onMain(() -> {
            invoke(first, "gpuiShowKeyboard", new Class<?>[]{int.class, long.class}, 0, 999999L);
            invoke(first, "gpuiRequestKeyboard", new Class<?>[]{long.class}, generation - 1);
            invoke(first, "gpuiShowKeyboard", new Class<?>[]{int.class, long.class}, 0, 999999L);
        });
        require(find(first.getWindow().getDecorView(), EditText.class) == null,
                "Focus/stale-generation request created an IME proxy without a Keyboard tap");
        pass("Unrequested and stale-generation soft-keyboard callbacks are rejected");

        // Exercise GPUI hit-testing and its real keyboard callback, not a test-only show method.
        SurfaceView surface = find(first.getWindow().getDecorView(), SurfaceView.class);
        require(surface != null, "GPUI SurfaceView missing");
        tap(surface, 48 * density(first), surface.getHeight() - 28 * density(first));
        await(() -> find(first.getWindow().getDecorView(), EditText.class) != null,
                "GPUI Keyboard button opens the Android IME proxy");
        onMain(() -> { });
        SystemClock.sleep(350);
        final EditText proxy = find(first.getWindow().getDecorView(), EditText.class);
        final InputConnection[] ime = new InputConnection[1];
        final EditorInfo editorInfo = new EditorInfo();
        onMain(() -> ime[0] = proxy.onCreateInputConnection(editorInfo));
        require((editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0,
                "IME personalized learning was not disabled");
        require((editorInfo.inputType & android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0,
                "IME suggestions were not disabled");
        pass("Actual IME connection disables personalized learning and suggestions");
        require(ime[0] != null, "IME InputConnection missing");
        int beforeOversized = healthy.posts().size();
        StringBuilder oversized = new StringBuilder(65537);
        for (int i = 0; i < 65537; i++) oversized.append('x');
        onMain(() -> requireUnchecked(!ime[0].commitText(oversized.toString(), 1), "Oversized IME commit accepted"));
        SystemClock.sleep(250);
        require(healthy.posts().size() == beforeOversized && ready(first), "Oversized IME text reached transport or closed healthy input");
        pass("Oversized 65,537-character IME commit is rejected before transport");
        onMain(() -> {
            long at = SystemClock.uptimeMillis();
            for (int code : new int[]{KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL}) {
                for (int modifier : new int[]{KeyEvent.META_SHIFT_ON, KeyEvent.META_CTRL_ON, KeyEvent.META_ALT_ON}) {
                    requireUnchecked(!ime[0].sendKeyEvent(new KeyEvent(at, at, KeyEvent.ACTION_DOWN, code, 0,
                            modifier, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD)),
                            "IME modified Enter/Delete was accepted");
                }
            }
        });
        SystemClock.sleep(250);
        require(healthy.posts().size() == beforeOversized, "IME modifier was silently stripped into terminal input");
        pass("IME Shift/Ctrl/Alt Enter/Delete events are rejected without stripping modifiers");
        int beforeNavigation = healthy.posts().size();
        onMain(() -> {
            requireUnchecked(ime[0].setComposingText("pending", 1), "Preedit rejected");
            for (int action : new int[]{EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_PREVIOUS,
                    -1, 100, EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_SEND}) {
                requireUnchecked(!ime[0].performEditorAction(action), "Non-submit editor action accepted");
                requireUnchecked("pending".contentEquals(proxy.getText()), "Navigation changed preedit");
            }
        });
        SystemClock.sleep(250);
        require(healthy.posts().size() == beforeNavigation && ready(first),
                "Editor navigation submitted terminal input or closed the connection");
        onMain(() -> requireUnchecked(ime[0].commitText("", 1), "Preedit cancellation rejected"));
        pass("IME navigation and unknown actions preserve preedit without submitting terminal input");
        int beforeComposition = healthy.posts().size();
        onMain(() -> {
            requireUnchecked(ime[0].setComposingText("draft", 1), "setComposingText rejected");
            button(first, "Ctrl-C").performClick();
        });
        SystemClock.sleep(200);
        require(healthy.posts().size() == beforeComposition, "Composition or blocked shortcut reached transport");
        capture("02-ime-composition");
        onMain(() -> {
            requireUnchecked(ime[0].commitText("VM α中🧪", 1), "commitText rejected");
            button(first, "Enter").performClick();
        });
        await(() -> healthy.posts().size() == beforeComposition + 2, "Unicode IME commit and extra Enter");
        requirePost(healthy, beforeComposition, "text", "VM α中🧪");
        requirePost(healthy, beforeComposition + 1, "keys", "enter");
        pass("Real InputConnection composition stays local; Unicode commit precedes extra Enter exactly once");
        pass("Shortcut input is blocked during active IME composition");

        int beforeShortcut = healthy.posts().size();
        Button ctrl = button(first, "Ctrl-C");
        onMain(() -> {
            long at = SystemClock.uptimeMillis();
            for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP}) {
                MotionEvent event = MotionEvent.obtain(at, at + action, action, ctrl.getWidth() / 2f, ctrl.getHeight() / 2f, 0);
                ctrl.dispatchTouchEvent(event); event.recycle();
            }
        });
        SystemClock.sleep(200);
        require(healthy.posts().size() == beforeShortcut, "Cancelled shortcut gesture sent a command");
        pass("Extra-key DOWN/CANCEL/UP gesture sends nothing");
        tap(ctrl, ctrl.getWidth() / 2f, ctrl.getHeight() / 2f);
        await(() -> healthy.posts().size() == beforeShortcut + 1, "extra-key touch");
        requirePost(healthy, beforeShortcut, "keys", "ctrl+c");
        pass("Extra-key MotionEvent touch path sends exactly one Ctrl-C");
        capture("03-input-accepted");
        if (keyboard >= 0) checkDialogIsolation(first, healthy, keyboard, ime[0]);

        // Stop the test scheduler so the subsequent queued command must remain unsent until pause.
        Object oldConnection = field(first, "connection");
        Object oldClient = field(oldConnection, "client");
        ((ScheduledFuture<?>) field(oldConnection, "poll")).cancel(false);
        // A barrier proves the previous pump ended before deliberately queuing unsent input.
        ((ScheduledThreadPoolExecutor) field(first, "worker")).submit(() -> { })
                .get(WAIT_MS, TimeUnit.MILLISECONDS);
        int postsBeforePause = healthy.posts().size();
        onMain(() -> { button(first, "Enter").performClick(); button(first, "Ctrl").performClick(); });
        getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME), true);
        getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME), true);
        await(() -> field(first, "connection") == null, "real Android HOME pause closes the connection");
        require((Boolean) method(oldClient.getClass(), "isClosed").invoke(oldClient), "Pause did not close old client");
        require(healthy.posts().size() == postsBeforePause, "Unsent command escaped after pause");
        resumeTerminal(first, generation);
        SystemClock.sleep(250);
        require(healthy.posts().size() == postsBeforePause, "Paused input was replayed on resume");
        require(!(Boolean) field(first, "controlLatched"), "One-shot Ctrl survived Activity pause");
        final boolean[] staleAccepted = new boolean[1];
        onMain(() -> staleAccepted[0] = (Boolean) invokeStatic(first.getClass(), "nativeSendKey",
                new Class<?>[]{long.class, String.class}, generation, "enter"));
        require(!staleAccepted[0], "Native FIFO accepted a stale lifecycle generation");
        pass("Real HOME/resume closes old client, replaces generation, discards queued input and rejects stale input");
        capture("04-resumed");

        setDisplayOptions(1, 18, 2);
        close();
        Fixture reopened = new Fixture("reopened");
        open(reopened, "VM fixture · reopened");
        await(() -> ready(terminal), "reopen screen");
        Object reopenedOptions = field(terminal, "displayOptions");
        require((Integer) field(reopenedOptions, "mode") == 1 && (Integer) field(reopenedOptions, "fontSize") == 18
                && (Integer) field(reopenedOptions, "altMode") == 2, "Reopening lost saved display preferences");
        pass("Compact profile, 18sp font and Terminal Alt override persist across terminal reopen");
        capture("05-reopened");
        pass("Close and reopen reuse the process-lived GPUI renderer with a new one-shot launch grant");
        setDisplayOptions(0, 0, 0);
        close();

        Fixture longOutput = new Fixture("long-output");
        open(longOutput, "VM fixture · follow while composing");
        await(() -> ready(terminal), "long output screen");
        capture("05a-long-follow");
        final Activity longActivity = terminal;
        SurfaceView longSurface = find(longActivity.getWindow().getDecorView(), SurfaceView.class);
        tap(longSurface, 48 * density(longActivity), longSurface.getHeight() - 28 * density(longActivity));
        await(() -> find(longActivity.getWindow().getDecorView(), EditText.class) != null,
                "long-output keyboard proxy");
        final InputConnection[] longIme = new InputConnection[1];
        onMain(() -> {
            EditText input = find(longActivity.getWindow().getDecorView(), EditText.class);
            longIme[0] = input.onCreateInputConnection(new EditorInfo());
            requireUnchecked(longIme[0] != null && longIme[0].setComposingText("local draft", 1), "Long preedit rejected");
        });
        capture("05b-long-follow-composition");
        require(longOutput.posts().isEmpty(), "Long-output preedit reached transport");
        pass("Long-output and composition frames captured for visual Follow inspection; preedit sends no command");
        close();

        for (String mode : new String[]{"http401", "timeout", "malformed"}) {
            Fixture fault = new Fixture(mode);
            open(fault, "VM fixture · " + mode);
            await(() -> clientClosed(terminal), "fail-closed " + mode);
            int requests = fault.requests().size();
            Button enter = button(terminal, "Enter");
            tap(enter, enter.getWidth() / 2f, enter.getHeight() / 2f);
            SystemClock.sleep(300);
            require(fault.posts().isEmpty(), "Error state accepted input for " + mode);
            require(fault.requests().size() == requests, "Error state retried without reopen for " + mode);
            capture("06-error-" + mode);
            pass("Synthetic " + mode + " fails closed with no automatic retry or post-error input");
            close();
        }
        Fixture mutation = new Fixture("bad-ack");
        open(mutation, "VM fixture · uncertain acknowledgement");
        await(() -> ready(terminal), "initial screen before bad acknowledgement");
        onMain(() -> button(terminal, "Enter").performClick());
        await(() -> clientClosed(terminal), "bad acknowledgement closes connection");
        require(mutation.posts().size() == 1, "Uncertain command was lost or replayed");
        capture("07-bad-ack");
        pass("Malformed mutation acknowledgement closes connection after exactly one attempted command");
        close();
        checkQuickCommandTransport();
        checkQuickCommandUi();
    }

    private AlertDialog activeDialog() throws Exception { return (AlertDialog) field(terminal, "displayDialog"); }

    private AlertDialog openPalette() throws Exception {
        onMain(() -> button(terminal, "Cmds").performClick());
        await(() -> activeDialog() != null && activeDialog().isShowing(), "Cmds palette opens");
        AlertDialog dialog = activeDialog();
        require(dialog.getListView() != null, "Cmds did not open a command list");
        require((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0,
                "Command list lost FLAG_SECURE");
        return dialog;
    }

    private AlertDialog nextDialog(AlertDialog previous, Runnable action) throws Exception {
        onMain(action);
        await(() -> activeDialog() != null && activeDialog() != previous && activeDialog().isShowing(),
                "next command dialog opens");
        return activeDialog();
    }

    private int commandRow(AlertDialog dialog, String label) {
        for (int i = 0; i < dialog.getListView().getAdapter().getCount(); i++) {
            String line = dialog.getListView().getAdapter().getItem(i).toString().split("\n", 2)[0];
            if (line.replace("★ ", "").equals(label)) return i;
        }
        return -1;
    }

    private AlertDialog preview(AlertDialog list, String label) throws Exception {
        int index = commandRow(list, label);
        require(index >= 0, "Missing saved command row: " + label);
        return nextDialog(list, () -> list.getListView().performItemClick(
                list.getListView().getAdapter().getView(index, null, list.getListView()), index,
                list.getListView().getAdapter().getItemId(index)));
    }

    private EditText commandEditor(AlertDialog dialog, String hint) {
        List<EditText> values = new ArrayList<>();
        findAll(dialog.getWindow().getDecorView(), EditText.class, values);
        for (EditText value : values) if (hint.contentEquals(value.getHint())) return value;
        throw new AssertionError("Missing command editor: " + hint);
    }

    private CheckBox commandCheckbox(AlertDialog dialog, String label) {
        List<CheckBox> values = new ArrayList<>();
        findAll(dialog.getWindow().getDecorView(), CheckBox.class, values);
        for (CheckBox value : values) if (label.contentEquals(value.getText())) return value;
        throw new AssertionError("Missing command checkbox: " + label);
    }

    private Button dialogButton(AlertDialog dialog, String label) {
        Button result = findButton(dialog.getWindow().getDecorView(), label);
        require(result != null, "Missing command button: " + label);
        return result;
    }

    private void cancelCommandDialog(AlertDialog dialog) throws Exception {
        onMain(() -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        await(() -> activeDialog() == null, "command dialog closes");
    }

    private void captureDialog(AlertDialog dialog, String name) throws Exception {
        await(() -> dialog.getWindow().getDecorView().getWidth() > 0
                && dialog.getWindow().getDecorView().getHeight() > 0, "command dialog layout");
        require((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0,
                "Command preview lost FLAG_SECURE");
        final Bitmap[] image = new Bitmap[1];
        onMain(() -> {
            View decor = dialog.getWindow().getDecorView();
            image[0] = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            decor.draw(new Canvas(image[0]));
        });
        saveBitmap(image[0], name + ".png"); image[0].recycle();
    }

    private void captureQuickCommandFrames(Fixture fixture) throws Exception {
        int before = fixture.posts().size();
        AlertDialog palette = openPalette();
        captureDialog(palette, "quick-command-palette");
        AlertDialog draft = preview(palette, "Context");
        checkPreviewPhysicalEnter(draft, fixture);
        captureDialog(draft, "quick-command-preview");
        capturePreviewActions(draft);
        cancelCommandDialog(draft);
        require(fixture.posts().size() == before, "Opening/cancelling quick-command frames sent input");
        pass("Secure Cmds palette and editable preview captured at the supplied display geometry; opening/cancelling sends nothing");
    }

    private void checkPreviewPhysicalEnter(AlertDialog draft, Fixture fixture) throws Exception {
        int keyboard = findPhysicalKeyboard();
        if (keyboard < 0) {
            skips.add("Command preview Enter/Numpad Enter: Android exposed no non-virtual keyboard device");
            return;
        }
        int before = fixture.posts().size();
        onMain(() -> {
            for (String hint : new String[]{"Name", "Command"}) {
                require(commandEditor(draft, hint).requestFocus(), "Could not focus command preview editor");
                for (int code : new int[]{KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER}) {
                    long at = SystemClock.uptimeMillis();
                    draft.dispatchKeyEvent(new KeyEvent(at, at, KeyEvent.ACTION_DOWN, code, 0, 0,
                            keyboard, 0, 0, InputDevice.SOURCE_KEYBOARD));
                    draft.dispatchKeyEvent(new KeyEvent(at, at + 1, KeyEvent.ACTION_UP, code, 0, 0,
                            keyboard, 0, 0, InputDevice.SOURCE_KEYBOARD));
                }
            }
        });
        // Drain any UI callback, then run the real pump on its owner worker before asserting no input.
        onMain(() -> { });
        Activity current = terminal;
        Object connection = field(current, "connection");
        ((ScheduledThreadPoolExecutor) field(current, "worker")).submit(() ->
                invoke(current, "pump", new Class<?>[]{connection.getClass()}, connection)).get(WAIT_MS, TimeUnit.MILLISECONDS);
        require(activeDialog() == draft && draft.isShowing() && fixture.posts().size() == before,
                "Physical Enter/Numpad Enter submitted input or dismissed the command preview");
        pass("Physical Enter and Numpad Enter in both native preview editors cause no POST and no implicit dismissal");
    }

    private void capturePreviewActions(AlertDialog draft) throws Exception {
        List<android.widget.ScrollView> scrolls = new ArrayList<>();
        findAll(draft.getWindow().getDecorView(), android.widget.ScrollView.class, scrolls);
        android.widget.ScrollView found = null;
        for (android.widget.ScrollView candidate : scrolls) {
            if (findButton(candidate, "Save command") != null) { found = candidate; break; }
        }
        require(found != null, "Command preview actions are not inside a scrollable container");
        final android.widget.ScrollView scroll = found;
        onMain(() -> scroll.scrollTo(0, Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight())));
        await(() -> dialogButton(draft, "Save command").getGlobalVisibleRect(new Rect()),
                "saved-command actions scroll into view");
        captureDialog(draft, "quick-command-preview-actions");
    }

    private void checkQuickCommandUi() throws Exception {
        Fixture fixture = new Fixture("quick-command-ui");
        open(fixture, "VM fixture · command palette");
        await(() -> ready(terminal), "command palette input connection");
        final Activity current = terminal;
        capture("quick-command-before-ime");
        SurfaceView surface = find(current.getWindow().getDecorView(), SurfaceView.class);
        tap(surface, 48 * density(current), surface.getHeight() - 28 * density(current));
        await(() -> find(current.getWindow().getDecorView(), EditText.class) != null, "IME before command preview");
        final InputConnection[] staleInput = new InputConnection[1];
        onMain(() -> staleInput[0] = find(current.getWindow().getDecorView(), EditText.class)
                .onCreateInputConnection(new EditorInfo()));
        require(staleInput[0] != null, "Missing IME before command preview");
        int keyboard = findPhysicalKeyboard();
        if (keyboard < 0) skips.add("Quick-command preview physical handler: Android exposed no non-virtual keyboard device");
        AlertDialog palette = openPalette();
        captureDialog(palette, "quick-command-palette");
        AlertDialog draft = preview(palette, "Context");
        final AlertDialog firstDraft = draft;
        require(!draft.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled(), "Submit lacks empty-prompt confirmation");
        onMain(() -> {
            for (String hint : new String[]{"Name", "Command"}) {
                EditText editor = commandEditor(firstDraft, hint);
                EditorInfo info = new EditorInfo();
                require(editor.onCreateInputConnection(info) != null, "Command editor has no local InputConnection");
                require((info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
                        && (info.inputType & android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0,
                        "Command editor did not disable personalized learning/suggestions");
                require(!editor.isSaveEnabled(), "Command editor text entered framework saved state");
            }
            require(!staleInput[0].commitText("STALE QUICK COMMAND INPUT", 1), "Old GPUI IME accepted preview text");
            require(!staleInput[0].commitText("STALE MODERN COMMAND INPUT", 1, null), "Old modern GPUI IME accepted preview text");
            if (keyboard >= 0) physical(current, keyboard, KeyEvent.KEYCODE_A, 0);
            commandEditor(firstDraft, "Command").onEditorAction(EditorInfo.IME_ACTION_DONE);
            firstDraft.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        });
        captureDialog(draft, "quick-command-preview");
        capturePreviewActions(draft);
        cancelCommandDialog(draft);
        workerBarrier(current);
        require(fixture.posts().isEmpty(), "Preview/physical/old IME/editor action or unconfirmed Send reached terminal");
        pass("Cmds preview isolates old GPUI IME" + (keyboard >= 0 ? " and physical Activity input" : "")
                + "; Cancel/IME Done/unconfirmed Send produce no POST");

        palette = openPalette();
        final AlertDialog presetsFrom = palette;
        AlertDialog presets = nextDialog(palette, () -> presetsFrom.getButton(AlertDialog.BUTTON_NEUTRAL).performClick());
        draft = preview(presets, "Context");
        cancelCommandDialog(draft);
        require(fixture.posts().isEmpty(), "Opening or cancelling OMP preset sent input");

        String otherCommands = commandJson(storedHost("synthetic-other"));
        palette = openPalette();
        final AlertDialog addFrom = palette;
        draft = nextDialog(palette, () -> addFrom.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        final AlertDialog added = draft;
        onMain(() -> {
            commandEditor(added, "Name").setText("VM pinned command");
            commandEditor(added, "Command").setText("/context VM_UI α中🧪");
            commandCheckbox(added, "Pin when saved").setChecked(true);
        });
        Object storeLock = staticField(hostStore.getClass(), "LOCK");
        CountDownLatch storeHeld = new CountDownLatch(1), releaseStore = new CountDownLatch(1);
        Throwable[] lockFailure = new Throwable[1];
        Thread holder = new Thread(() -> {
            synchronized (storeLock) {
                storeHeld.countDown();
                try { require(releaseStore.await(WAIT_MS, TimeUnit.MILLISECONDS), "Synthetic store lock was not released"); }
                catch (Throwable error) { lockFailure[0] = error; }
            }
        }, "vm-hold-host-store");
        holder.setDaemon(true); holder.start();
        require(storeHeld.await(WAIT_MS, TimeUnit.MILLISECONDS), "Could not hold test HostStore lock");
        try {
            onMain(() -> {
                dialogButton(added, "Save command").performClick();
                added.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
            await(() -> activeDialog() == null, "Cancel closes accepted pending save");
            onMain(() -> button(current, "Cmds").performClick());
            require(activeDialog() == null && (Boolean) field(field(current, "commandPalette"), "saving"),
                    "Reopening Cmds during a pending save created a stale disabled list");
        } finally {
            releaseStore.countDown();
            holder.join(WAIT_MS);
        }
        require(!holder.isAlive() && lockFailure[0] == null, "Synthetic HostStore lock cleanup failed");
        await(() -> !(Boolean) field(field(current, "commandPalette"), "saving"), "Accepted save completes after dialog closes");
        palette = openPalette();
        pass("Accepted async save survives Cancel; Cmds waits for it and reopens an enabled saved-command list afterward");
        require(commandRow(palette, "VM pinned command") >= 0, "Saved command missing from palette");
        require(palette.getListView().getAdapter().getItem(commandRow(palette, "VM pinned command")).toString().startsWith("★ "),
                "Saved command pin not shown");
        require(commandJson(storedHost("synthetic-other")).equals(otherCommands), "UI save changed other host");
        draft = preview(palette, "VM pinned command");
        require(commandEditor(draft, "Command").getText().toString().equals("/context VM_UI α中🧪")
                && commandCheckbox(draft, "Pin when saved").isChecked(), "Saved Unicode command/pin lost");
        final AlertDialog edited = draft;
        onMain(() -> {
            commandEditor(edited, "Name").setText("VM edited command");
            commandEditor(edited, "Command").setText("/context VM_EDITED α");
            commandCheckbox(edited, "Pin when saved").setChecked(false);
        });
        palette = nextDialog(draft, () -> dialogButton(edited, "Save command").performClick());
        require(commandRow(palette, "VM pinned command") == -1 && commandRow(palette, "VM edited command") >= 0,
                "Editing command duplicated or lost it");
        require(!palette.getListView().getAdapter().getItem(commandRow(palette, "VM edited command")).toString().startsWith("★ "),
                "Unpin did not update palette");
        cancelCommandDialog(palette);
        workerBarrier(current);
        require(fixture.posts().isEmpty(), "Add/save/edit/pin/unpin sent remote input");
        pass("Native command widgets add, save, edit, pin and unpin Unicode commands per host without sending input");
        close();

        Fixture reopened = new Fixture("quick-command-ui-reopened");
        open(reopened, "VM fixture · saved commands reopened");
        await(() -> ready(terminal), "reopened command palette");
        palette = openPalette();
        draft = preview(palette, "VM edited command");
        require(commandEditor(draft, "Command").getText().toString().equals("/context VM_EDITED α")
                && !commandCheckbox(draft, "Pin when saved").isChecked(), "Reopen lost saved command edits");
        final AlertDialog insert = draft;
        onMain(() -> {
            commandEditor(insert, "Command").setText("/context VM_INSERT");
            insert.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
        await(() -> reopened.posts().size() == 1, "preview Insert sends only text");
        requirePost(reopened, 0, "text", "/context VM_INSERT");
        workerBarrier(terminal);
        require(reopened.posts().size() == 1, "Insert appended Enter");
        palette = openPalette();
        draft = preview(palette, "VM edited command");
        final AlertDialog submit = draft;
        onMain(() -> {
            CheckBox empty = commandCheckbox(submit, "OMP is idle and its input prompt is empty");
            empty.setChecked(true);
            require(submit.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled(), "Confirmed Submit remains unavailable");
            commandEditor(submit, "Command").setText("/context VM_SUBMIT");
            require(!empty.isChecked() && !submit.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled(),
                    "Editing command retained stale empty-prompt confirmation");
            empty.setChecked(true);
            submit.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            submit.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        });
        await(() -> reopened.posts().size() == 3, "preview confirmed Submit sends text then Enter");
        requirePost(reopened, 1, "text", "/context VM_SUBMIT"); requirePost(reopened, 2, "keys", "enter");
        workerBarrier(terminal);
        require(reopened.posts().size() == 3, "Repeated Submit click queued input twice");
        pass("Saved command survives close/reopen; Insert sends text only; explicit confirmed Send + Enter sends once and edits reset confirmation");

        palette = openPalette();
        draft = preview(palette, "VM edited command");
        final AlertDialog deleteFrom = draft;
        AlertDialog confirmation = nextDialog(draft, () -> dialogButton(deleteFrom, "Delete").performClick());
        final AlertDialog cancelDelete = confirmation;
        draft = nextDialog(confirmation, () -> cancelDelete.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        require(commandRow(openListAfterCancelPreview(draft), "VM edited command") >= 0, "Cancel delete removed command");
        palette = activeDialog();
        draft = preview(palette, "VM edited command");
        final AlertDialog deleteAgain = draft;
        confirmation = nextDialog(draft, () -> dialogButton(deleteAgain, "Delete").performClick());
        final AlertDialog deleteConfirm = confirmation;
        palette = nextDialog(confirmation, () -> deleteConfirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        require(commandRow(palette, "VM edited command") == -1, "Confirmed delete retained saved command");
        cancelCommandDialog(palette);
        require(reopened.posts().size() == 3, "Delete/cancel sent remote input");
        pass("Delete requires confirmation; cancellation preserves command and confirmed removal sends nothing");

        palette = openPalette();
        final AlertDialog stale = preview(palette, "Context");
        final AlertDialog staleDelete = nextDialog(stale, () -> dialogButton(stale, "Delete").performClick());
        final Activity resumed = terminal;
        final long oldGeneration = generation(resumed);
        sendHome();
        await(() -> field(resumed, "connection") == null && !staleDelete.isShowing(), "HOME closes command dialog");
        resumeTerminal(resumed, oldGeneration);
        onMain(() -> {
            stale.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            staleDelete.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        });
        AlertDialog fresh = openPalette();
        onMain(() -> {
            stale.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            staleDelete.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        });
        workerBarrier(resumed);
        SystemClock.sleep(250);
        require(reopened.posts().size() == 3 && activeDialog() == fresh,
                "Stale modal targeted the resumed connection or replaced a fresh dialog");
        cancelCommandDialog(fresh);
        pass("HOME dismisses command dialogs; stale Insert and Delete-Cancel buttons cannot send to a resumed connection or replace a fresh palette");
        close();
    }

    private AlertDialog openListAfterCancelPreview(AlertDialog draft) throws Exception {
        cancelCommandDialog(draft);
        return openPalette();
    }

    private boolean queueQuickCommand(Activity current, String text, boolean submit) throws Exception {
        String json = (String) invokeStatic(targetClass("dev.herdr.android.GpuiTerminalProtocol"), "quickCommand",
                new Class<?>[]{String.class, boolean.class}, text, submit);
        return (Boolean) invokeStatic(current.getClass(), "nativeSendCommand",
                new Class<?>[]{long.class, String.class}, generation(current), json);
    }

    private void queueQuickCommandOnMain(Activity current, String text, boolean submit) {
        onMain(() -> {
            try { require(queueQuickCommand(current, text, submit), "Quick command was not queued"); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
    }

    private void workerBarrier(Activity current) throws Exception {
        ((ScheduledThreadPoolExecutor) field(current, "worker")).submit(() -> { }).get(WAIT_MS, TimeUnit.MILLISECONDS);
    }

    private void sendHome() {
        getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME), true);
        getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME), true);
    }

    private void wakeScreen() {
        getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_WAKEUP), true);
        getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_WAKEUP), true);
        // A software-emulator SystemUI restart can restore the ordinary swipe keyguard.
        // Match run.sh's transient dismissal; do not alter lock settings or credentials.
        try (InputStream command = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                getUiAutomation().executeShellCommand("wm dismiss-keyguard"))) {
            java.util.concurrent.FutureTask<Void> drained = new java.util.concurrent.FutureTask<>(() -> {
                byte[] buffer = new byte[256];
                while (command.read(buffer) != -1) { }
                return null;
            });
            Thread reader = new Thread(drained, "vm-dismiss-keyguard");
            reader.setDaemon(true); reader.start();
            drained.get(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception error) { throw new IllegalStateException("Could not dismiss test-VM keyguard", error); }
    }

    private void resumeTerminal(Activity current, long oldGeneration) throws Exception {
        wakeScreen();
        onMain(() -> launcher.startActivity(new Intent().setClassName(targetPackage, TERMINAL)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        await(() -> field(current, "connection") != null && generation(current) > oldGeneration && ready(current),
                "fresh quick-command connection after resume");
    }

    private void checkQuickCommandTransport() throws Exception {
        Fixture ordered = new Fixture("quick-command-order");
        open(ordered, "VM fixture · ordered quick commands");
        await(() -> ready(terminal), "quick-command FIFO screen");
        Activity current = terminal;
        Object connection = field(current, "connection");
        ((ScheduledFuture<?>) field(connection, "poll")).cancel(false);
        workerBarrier(current);
        queueQuickCommandOnMain(current, "before-submit", false);
        queueQuickCommandOnMain(current, "/context α中🧪", true);
        queueQuickCommandOnMain(current, "after-submit", false);
        scheduleRealPump(current);
        await(() -> ordered.posts().size() == 4, "one Submit FIFO entry expands to text then Enter");
        requirePost(ordered, 0, "text", "before-submit");
        requirePost(ordered, 1, "text", "/context α中🧪");
        requirePost(ordered, 2, "keys", "enter");
        requirePost(ordered, 3, "text", "after-submit");
        workerBarrier(current);
        require(ordered.posts().size() == 4, "Quick-command FIFO repeated a command");
        pass("Native quick-command FIFO preserves Insert, Submit text → valid ACK → Enter, then following input exactly once");
        close();

        Fixture invalid = new Fixture("bad-text-ack");
        open(invalid, "VM fixture · rejected quick-command text ACK");
        await(() -> ready(terminal), "screen before quick-command ACK failure");
        queueQuickCommandOnMain(terminal, "/context", true);
        await(() -> clientClosed(terminal), "text ACK rejection fails closed before Enter");
        workerBarrier(terminal);
        require(invalid.posts().size() == 1, "Rejected text ACK sent Enter or replayed text");
        requirePost(invalid, 0, "text", "/context");
        pass("Rejected quick-command text acknowledgement closes input after one text request; no Enter and no replay");
        close();

        Fixture paused = new Fixture("quick-command-pause-after-text");
        open(paused, "VM fixture · pause before quick-command Enter");
        await(() -> ready(terminal), "screen before quick-command pause");
        final Activity pausedActivity = terminal;
        final long oldGeneration = generation(pausedActivity);
        CountDownLatch detached = new CountDownLatch(1);
        paused.afterTextResponse = () -> {
            try {
                // disconnect() runs after valid response bytes are decoded, before sendText returns.
                // Detach here deterministically; the real pump must fence its second request.
                sendHome();
                await(() -> field(pausedActivity, "connection") == null, "HOME detaches between text response and Enter");
                detached.countDown();
            } catch (Throwable error) { lifecycleFailure = unwrap(error); }
        };
        queueQuickCommandOnMain(pausedActivity, "/context", true);
        require(detached.await(WAIT_MS, TimeUnit.MILLISECONDS), "Quick-command response pause did not complete");
        workerBarrier(pausedActivity);
        require(lifecycleFailure == null, "Quick-command pause callback failed");
        require(paused.posts().size() == 1, "Quick-command Enter escaped after HOME");
        resumeTerminal(pausedActivity, oldGeneration);
        workerBarrier(pausedActivity);
        SystemClock.sleep(250);
        require(paused.posts().size() == 1, "Quick command replayed after resume");
        requirePost(paused, 0, "text", "/context");
        pass("Real HOME after a valid synthetic text response suppresses Submit Enter and resume never replays input");
        close();
    }

    private void restoreDisplayPreferences() {
        if (displayPreferences == null || originalDisplayPreferences == null) return;
        SharedPreferences.Editor editor = displayPreferences.edit();
        for (String key : new String[]{"mode", "font", "alt"}) {
            editor.remove(key);
            Object value = originalDisplayPreferences.get(key);
            if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value != null) throw new AssertionError("Unsupported original preference type for " + key);
        }
        require(editor.commit(), "Could not restore local display preferences");
    }

    private Object syntheticHost(String id, String name, String url) throws Exception {
        Class<?> profileType = targetClass("dev.herdr.android.core.HostProfile");
        Object profile = profileType.getConstructor(String.class, String.class, String.class)
                .newInstance(name, url, "synthetic-test-token-not-a-secret");
        Class<?> hostType = targetClass("dev.herdr.android.SavedHost");
        Constructor<?> constructor = hostType.getDeclaredConstructor(String.class, profileType, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(id, profile,
                "Ctrl-C = key:ctrl+c\nEnter = key:enter\nTab = key:tab\nEscape = key:esc\nLeft = key:left\nRight = key:right\nDown = key:down\nUp = key:up");
    }

    private Object storedHost(String id) throws Exception {
        for (Object host : (List<?>) invoke(hostStore, "read", new Class<?>[0])) {
            if (id.equals(field(host, "id"))) return host;
        }
        throw new AssertionError("Synthetic host missing from test store");
    }

    private String commandJson(Object host) throws Exception {
        return ((JSONObject) invoke(host, "toJson", new Class<?>[0])).getJSONArray("quickCommands").toString();
    }

    private void requireStoreFence(String name, Class<?>[] types, Object... args) throws Exception {
        try {
            method(hostStore.getClass(), name, types).invoke(hostStore, args);
            throw new AssertionError("Stale profile was accepted by " + name);
        } catch (InvocationTargetException rejected) {
            require(unwrap(rejected) instanceof IllegalStateException, "Unexpected stale profile rejection type");
        }
    }

    private void checkQuickCommandStorage() throws Exception {
        Class<?> storeType = targetClass("dev.herdr.android.HostStore");
        Constructor<?> constructor = storeType.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        hostStore = constructor.newInstance(getTargetContext());
        originalHosts = new ArrayList<>((List<?>) invoke(hostStore, "read", new Class<?>[0]));
        Object first = syntheticHost("synthetic-vm", "SYNTHETIC VM", "https://synthetic.invalid/fixture/");
        Object other = syntheticHost("synthetic-other", "SYNTHETIC OTHER", "https://synthetic.invalid/fixture/");
        List<Object> hosts = new ArrayList<>(); hosts.add(first); hosts.add(other);
        invoke(hostStore, "write", new Class<?>[]{List.class}, hosts);
        String otherCommands = commandJson(other);
        Class<?> commandType = targetClass("dev.herdr.android.core.QuickCommand");
        String sentinel = "VM_STORAGE_SENTINEL_α中🧪";
        Object command = commandType.getConstructor(String.class, String.class, boolean.class)
                .newInstance("VM saved label α", "/context " + sentinel, true);
        List<Object> commands = new ArrayList<>(); commands.add(command);
        Object profile = field(first, "profile");
        Class<?> profileType = profile.getClass();
        Class<?>[] updateTypes = {String.class, profileType, List.class};
        Object updated = invoke(hostStore, "updateQuickCommands", updateTypes, "synthetic-vm", profile, commands);
        require(commandJson(storedHost("synthetic-vm")).equals(commandJson(updated)),
                "Encrypted store lost quick-command label/text/pin");
        require(commandJson(storedHost("synthetic-other")).equals(otherCommands),
                "Saving commands changed another host");
        File encrypted = new File(getTargetContext().getNoBackupFilesDir(), "hosts-v1.enc");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream stream = new FileInputStream(encrypted)) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
        }
        byte[] payload = bytes.toByteArray();
        String raw = new String(payload, StandardCharsets.UTF_8);
        require(payload.length > 30 && payload[0] == 1 && !raw.contains(sentinel)
                && !raw.contains("VM saved label") && !raw.contains("synthetic-test-token-not-a-secret"),
                "Encrypted host file exposed a synthetic cleartext value");
        pass("Real Android Keystore HostStore preserves per-host quick-command label, Unicode text and pin without cleartext at rest");

        Object renamedProfile = profileType.getConstructor(String.class, String.class, String.class)
                .newInstance("SYNTHETIC RENAMED", "https://synthetic.invalid/fixture/", "synthetic-test-token-not-a-secret");
        invoke(hostStore, "saveProfile", new Class<?>[]{first.getClass(), profileType}, updated, renamedProfile);
        Object renamed = storedHost("synthetic-vm");
        require(commandJson(renamed).equals(commandJson(updated)), "Profile edit discarded commands");
        invoke(hostStore, "updateShortcuts", new Class<?>[]{first.getClass(), String.class}, renamed, "Enter = key:enter");
        require(commandJson(storedHost("synthetic-vm")).equals(commandJson(updated)), "Shortcut edit discarded commands");
        Object replaced = syntheticHost("synthetic-vm", "SYNTHETIC REPLACED", "https://synthetic-replaced.invalid/fixture/");
        hosts.set(0, replaced);
        invoke(hostStore, "write", new Class<?>[]{List.class}, hosts);
        String replacementCommands = commandJson(storedHost("synthetic-vm"));
        requireStoreFence("updateQuickCommands", updateTypes, "synthetic-vm", profile, commands);
        requireStoreFence("deleteHost", new Class<?>[]{first.getClass()}, first);
        require(commandJson(storedHost("synthetic-vm")).equals(replacementCommands), "Stale editor changed replacement host");
        hosts.remove(0);
        invoke(hostStore, "write", new Class<?>[]{List.class}, hosts);
        requireStoreFence("updateQuickCommands", updateTypes, "synthetic-vm", profile, commands);
        pass("Profile/shortcut edits preserve commands; changed-origin and deleted-host editors fail closed");
        hosts.add(0, first);
        invoke(hostStore, "write", new Class<?>[]{List.class}, hosts);
    }

    private void restoreHosts() throws Exception {
        if (hostStore == null || originalHosts == null) return;
        invoke(hostStore, "write", new Class<?>[]{List.class}, originalHosts);
        List<?> restored = (List<?>) invoke(hostStore, "read", new Class<?>[0]);
        require(restored.size() == originalHosts.size(), "Original test-app profile count was not restored");
        for (int i = 0; i < restored.size(); i++) {
            require(invoke(restored.get(i), "toJson", new Class<?>[0]).toString()
                    .equals(invoke(originalHosts.get(i), "toJson", new Class<?>[0]).toString()),
                    "Original test-app profile data was not restored");
        }
        pass("Original test-app profile list restored and verified after synthetic encrypted-storage tests");
    }

    private void checkGeometry(String label, boolean expectedCompact) throws Exception {
        Activity current = terminal;
        onMain(() -> { });
        View decor = current.getWindow().getDecorView();
        View root = (View) field(current, "terminalRoot");
        SurfaceView surface = find(decor, SurfaceView.class);
        WindowInsets insets = decor.getRootWindowInsets();
        require(insets != null, "Missing applied window insets");
        android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
        Rect bounds = bounds(surface), viewBounds = bounds(button(current, "View"));
        require(bounds.left >= safe.left && bounds.top >= safe.top, "Native surface overlaps top/left safe inset");
        require(bounds.right <= decor.getWidth() - safe.right, "Native surface overlaps right safe inset");
        require(viewBounds.bottom <= decor.getHeight() - safe.bottom, "Options row overlaps bottom safe inset");
        require(bounds.bottom <= viewBounds.top, "Surface and native options row overlap");
        require(bounds.width() > 200 * density(current) && bounds.height() > 64 * density(current),
                "Native terminal has no usable compact area");
        require(viewBounds.height() >= 47 * density(current), "View touch target is less than 48dp");
        require((Boolean) field(current, "compactDisplay") == expectedCompact, "Wrong resolved compact profile");
        Rect visible = new Rect();
        require(button(current, "View").getGlobalVisibleRect(visible)
                && visible.width() == viewBounds.width() && visible.height() == viewBounds.height(),
                "View button is clipped");
        observations.put(new JSONObject().put("geometry", label).put("density", density(current))
                .put("font_scale", current.getResources().getConfiguration().fontScale)
                .put("decor_width_px", decor.getWidth()).put("decor_height_px", decor.getHeight())
                .put("available_width_dp", (root.getWidth() - root.getPaddingLeft() - root.getPaddingRight()) / density(current))
                .put("available_height_dp", (root.getHeight() - root.getPaddingTop() - root.getPaddingBottom()) / density(current))
                .put("surface", bounds.toShortString()).put("view_button", viewBounds.toShortString())
                .put("insets", safe.toString()).put("compact", expectedCompact));
        pass("Measured " + label + " surface and 48dp View control fit system/IME safe insets");
    }

    private void checkDisplayChanges(Fixture fixture) throws Exception {
        Activity current = terminal;
        int before = fixture.posts().size();
        long initialHash = lastFrameHash;
        int compactHeader = lastHeaderBottom;
        int compactInk = lastTerminalInkHeight;
        setDisplayOptions(2, 0, 0);
        await(() -> !(Boolean) field(current, "compactDisplay"), "Standard layout override applied");
        captureDifferent("01-standard-override", initialHash);
        require(lastHeaderBottom > compactHeader, "Standard header did not grow relative to compact header");
        checkGeometry("standard-override", false);
        pass("View dialog switches actual native compact/standard header geometry without reconnecting");
        long standardHash = lastFrameHash;
        setDisplayOptions(1, 18, 0);
        await(() -> (Boolean) field(current, "compactDisplay"), "Compact layout override applied");
        captureDifferent("01-compact-font18", standardHash);
        require(lastTerminalInkHeight > compactInk, "18sp selection did not increase native terminal glyph height");
        int largeInk = lastTerminalInkHeight;
        long largeHash = lastFrameHash;
        setDisplayOptions(1, 11, 0);
        captureDifferent("01-compact-font11", largeHash);
        require(lastTerminalInkHeight < largeInk, "11sp selection did not reduce native terminal glyph height");
        pass("View text-size changes alter actual native terminal glyph pixels at 11sp and 18sp");
        setDisplayOptions(0, 0, 0);
        capture("01-auto-restored");
        require(fixture.posts().size() == before, "Local display controls sent a terminal command");
        HorizontalScrollView extras = find(current.getWindow().getDecorView(), HorizontalScrollView.class);
        require(extras != null && extras.getChildAt(0).getWidth() > extras.getWidth(), "Configured extras do not overflow for scroll test");
        Rect pinned = bounds(button(current, "View"));
        onMain(() -> extras.scrollTo(extras.getChildAt(0).getWidth(), 0));
        onMain(() -> { });
        require(extras.getScrollX() > 0, "Extra-key row did not scroll");
        require(bounds(button(current, "View")).equals(pinned), "View control moved with scrolling extras");
        capture("01-extras-scrolled");
        onMain(() -> extras.scrollTo(0, 0));
        pass("Configured extra keys scroll horizontally while the View control stays pinned");
    }

    private AlertDialog openDisplayDialog() throws Exception {
        onMain(() -> button(terminal, "View").performClick());
        await(() -> field(terminal, "displayDialog") != null, "View options dialog opens");
        AlertDialog dialog = (AlertDialog) field(terminal, "displayDialog");
        require(dialog.isShowing(), "View dialog is not visible");
        require((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0,
                "Options dialog does not preserve FLAG_SECURE");
        return dialog;
    }

    private void setDisplayOptions(int mode, int font, int alt) throws Exception {
        AlertDialog dialog = openDisplayDialog();
        List<Spinner> spinners = new ArrayList<>();
        findAll(dialog.getWindow().getDecorView(), Spinner.class, spinners);
        require(spinners.size() == 3, "Expected Layout, Text size and Physical Alt spinners");
        onMain(() -> {
            spinners.get(0).setSelection(mode);
            spinners.get(1).setSelection(font == 0 ? 0 : font - 10);
            spinners.get(2).setSelection(alt);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
        await(() -> field(terminal, "displayDialog") == null, "View options applied and dialog dismissed");
        Object options = field(terminal, "displayOptions");
        require((Integer) field(options, "mode") == mode && (Integer) field(options, "fontSize") == font
                && (Integer) field(options, "altMode") == alt, "Display options did not apply");
        require(displayPreferences.getInt("mode", -1) == mode && displayPreferences.getInt("font", -1) == font
                && displayPreferences.getInt("alt", -1) == alt, "Display choices were not saved locally");
    }

    private void checkPhysicalCompactInput(Activity current, Fixture fixture, int keyboard) throws Exception {
        int before = fixture.posts().size();
        Button ctrl = button(current, "Ctrl");
        onMain(() -> {
            long at = SystemClock.uptimeMillis();
            for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP}) {
                MotionEvent event = MotionEvent.obtain(at, at + action, action, ctrl.getWidth() / 2f, ctrl.getHeight() / 2f, 0);
                ctrl.dispatchTouchEvent(event); event.recycle();
            }
        });
        require(!(Boolean) field(current, "controlLatched"), "Cancelled Ctrl gesture armed the modifier");
        pass("Cancelled one-shot Ctrl touch leaves the modifier unarmed");
        onMain(() -> {
            dispatchTap(ctrl, ctrl.getWidth() / 2f, ctrl.getHeight() / 2f);
            physical(current, keyboard, KeyEvent.KEYCODE_C, 0);
            physical(current, keyboard, KeyEvent.KEYCODE_C, 0);
        });
        await(() -> fixture.posts().size() == before + 2, "one-shot Ctrl and subsequent normal letter");
        requirePost(fixture, before, "keys", "ctrl+c");
        requirePost(fixture, before + 1, "text", "c");
        require(!(Boolean) field(current, "controlLatched"), "One-shot Ctrl did not clear");
        pass("One-shot Ctrl sends Ctrl-C once, then the next physical C is ordinary text");
        int beforeRepeat = fixture.posts().size();
        onMain(() -> {
            dispatchTap(ctrl, ctrl.getWidth() / 2f, ctrl.getHeight() / 2f);
            long at = SystemClock.uptimeMillis();
            current.dispatchKeyEvent(new KeyEvent(at, at, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, 0,
                    keyboard, 0, 0, InputDevice.SOURCE_KEYBOARD));
            current.dispatchKeyEvent(new KeyEvent(at, at + 20, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 1, 0,
                    keyboard, 0, 0, InputDevice.SOURCE_KEYBOARD));
            current.dispatchKeyEvent(new KeyEvent(at, at + 30, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, 0, 0,
                    keyboard, 0, 0, InputDevice.SOURCE_KEYBOARD));
        });
        await(() -> fixture.posts().size() == beforeRepeat + 1, "one-shot Ctrl with a held/repeating letter");
        SystemClock.sleep(300);
        require(fixture.posts().size() == beforeRepeat + 1, "Held Ctrl-modified letter repeated or became plain text");
        requirePost(fixture, beforeRepeat, "keys", "ctrl+c");
        pass("Held/repeated one-shot Ctrl-C produces one command and no unmodified repeat");

        int beforeUnsupported = fixture.posts().size();
        onMain(() -> {
            physical(current, keyboard, KeyEvent.KEYCODE_C, KeyEvent.META_META_ON);
            physical(current, keyboard, KeyEvent.KEYCODE_C, KeyEvent.META_SYM_ON);
            physical(current, keyboard, KeyEvent.KEYCODE_C, KeyEvent.META_FUNCTION_ON);
            physical(current, keyboard, KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON);
            physical(current, keyboard, KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON);
            physical(current, keyboard, KeyEvent.KEYCODE_ENTER, KeyEvent.META_ALT_ON);
            physical(current, keyboard, KeyEvent.KEYCODE_DEL, KeyEvent.META_ALT_ON);
        });
        SystemClock.sleep(350);
        require(fixture.posts().size() == beforeUnsupported && ready(current), "Unsupported modifiers sent a command or closed input");
        pass("Meta/Sym/Fn/Ctrl-Alt/Ctrl-Shift and Alt+Enter/Backspace produce no POST or silently stripped key");

        InputDevice device = InputDevice.getDevice(keyboard);
        KeyCharacterMap map = device.getKeyCharacterMap();
        int chosen = -1, unicode = 0, base = 0;
        for (int code = KeyEvent.KEYCODE_A; code <= KeyEvent.KEYCODE_Z; code++) {
            int candidate = map.get(code, KeyEvent.META_ALT_ON);
            int plain = map.get(code, 0);
            if (plain < 'a' || plain > 'z' || !printable(candidate)) continue;
            if (chosen < 0 || candidate != plain) { chosen = code; unicode = candidate; base = plain; }
            if (candidate != plain) break;
        }
        final boolean syntheticSymbol = chosen < 0;
        if (syntheticSymbol) {
            skips.add("Actual keyboard Alt-symbol mapping: emulator KeyCharacterMap has no printable Alt-letter; Titan firmware still requires device testing");
            chosen = KeyEvent.KEYCODE_C; unicode = '@'; base = 'c';
        }
        final int altCode = chosen;
        final int altUnicode = unicode;
        final String symbol = new String(Character.toChars(unicode));
        final String chord = "alt+" + (char) base;
        observations.put(new JSONObject().put("physical_keyboard_id", keyboard).put("physical_keyboard_name", device.getName())
                .put("alt_keycode", chosen).put("alt_unicode", unicode).put("plain_unicode", base)
                .put("alt_text", symbol).put("terminal_alt_chord", chord)
                .put("source", syntheticSymbol ? "Explicit synthetic Unicode override; real emulator has no Alt symbol table"
                        : "Actual emulator InputDevice KeyCharacterMap, not a Titan firmware assumption"));
        int altBefore = fixture.posts().size();
        onMain(() -> physicalAltSymbol(current, keyboard, altCode, altUnicode, syntheticSymbol));
        await(() -> fixture.posts().size() == altBefore + 1, "device Alt symbol");
        requirePost(fixture, altBefore, "text", symbol);
        setDisplayOptions(2, 0, 0);
        int standardBefore = fixture.posts().size();
        onMain(() -> physicalAltSymbol(current, keyboard, altCode, altUnicode, syntheticSymbol));
        await(() -> fixture.posts().size() == standardBefore + 1, "Auto Alt after Standard layout override");
        requirePost(fixture, standardBefore, "text", symbol);
        setDisplayOptions(1, 0, 2);
        int overrideBefore = fixture.posts().size();
        onMain(() -> physicalAltSymbol(current, keyboard, altCode, altUnicode, syntheticSymbol));
        await(() -> fixture.posts().size() == overrideBefore + 1, "Terminal Alt override");
        requirePost(fixture, overrideBefore, "keys", chord);
        setDisplayOptions(0, 0, 0);
        pass(syntheticSymbol ? "Synthetic Alt Unicode is forwarded as text across layout changes; explicit Terminal Alt emits alt+c"
                : "Auto Alt uses the actual emulator KeyCharacterMap across layout changes; explicit Terminal Alt emits the matching chord");
    }

    private void checkDialogIsolation(Activity current, Fixture fixture, int keyboard, InputConnection previousInput) throws Exception {
        onMain(() -> button(current, "Ctrl").performClick());
        require((Boolean) field(current, "controlLatched"), "Cannot arm Ctrl before dialog isolation check");
        int before = fixture.posts().size();
        AlertDialog dialog = openDisplayDialog();
        require(!(Boolean) field(current, "controlLatched"), "Opening View left one-shot Ctrl armed");
        onMain(() -> {
            physical(current, keyboard, KeyEvent.KEYCODE_A, 0);
            if (previousInput != null) {
                requireUnchecked(!previousInput.commitText("STALE DIALOG INPUT", 1),
                        "Old InputConnection accepted text while options were open");
                requireUnchecked(!previousInput.commitText("STALE MODERN DIALOG INPUT", 1, null),
                        "Old modern InputConnection accepted text while options were open");
            }
            long at = SystemClock.uptimeMillis();
            dialog.dispatchKeyEvent(new KeyEvent(at, at, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0,
                    KeyEvent.META_CTRL_ON, keyboard, 0, 0, InputDevice.SOURCE_KEYBOARD));
            dialog.dispatchKeyEvent(new KeyEvent(at, at + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C, 0,
                    KeyEvent.META_CTRL_ON, keyboard, 0, 0, InputDevice.SOURCE_KEYBOARD));
        });
        SystemClock.sleep(350);
        require(fixture.posts().size() == before, "Physical input leaked from display dialog to terminal");
        final Bitmap[] image = new Bitmap[1];
        onMain(() -> {
            View decor = dialog.getWindow().getDecorView();
            image[0] = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            decor.draw(new Canvas(image[0]));
        });
        saveBitmap(image[0], "options-dialog-" + before + ".png");
        image[0].recycle();
        onMain(() -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        await(() -> field(current, "displayDialog") == null, "Cancel dismisses View dialog");
        require(!(Boolean) field(current, "controlLatched"), "Dialog dismissal restored an old Ctrl latch");
        if (previousInput != null) {
            onMain(() -> requireUnchecked(!previousInput.commitText("STALE AFTER DISMISS", 1, null),
                    "Dialog dismissal reauthorized an old modern InputConnection"));
            SystemClock.sleep(250);
            require(fixture.posts().size() == before, "Stale IME text leaked after options dismissed");
        }
        pass("Secure View dialog isolates physical input and clears Ctrl" + (find(current.getWindow().getDecorView(), EditText.class) == null
                ? " before IME creation" : " after IME use"));
    }

    private static boolean printable(int codepoint) {
        return Character.isValidCodePoint(codepoint) && codepoint >= 0x20 && codepoint != 0x7f
                && !(codepoint >= 0xd800 && codepoint <= 0xdfff)
                && (codepoint & KeyCharacterMap.COMBINING_ACCENT) == 0;
    }

    private static Rect bounds(View view) {
        int[] origin = new int[2];
        view.getLocationInWindow(origin);
        return new Rect(origin[0], origin[1], origin[0] + view.getWidth(), origin[1] + view.getHeight());
    }

    private static <T> void findAll(View view, Class<T> type, List<T> out) {
        if (type.isInstance(view)) out.add(type.cast(view));
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            findAll(((ViewGroup) view).getChildAt(i), type, out);
        }
    }

    private void open(Fixture fixture, String title) throws Exception {
        wakeScreen();
        pendingFixture = fixture;
        fixtures.add(fixture);
        Class<?> hostType = targetClass("dev.herdr.android.SavedHost");
        Object host = hostStore == null ? syntheticHost("synthetic-vm", "SYNTHETIC VM", "https://synthetic.invalid/fixture/")
                : storedHost("synthetic-vm");
        Class<?> launchType = targetClass("dev.herdr.android.GpuiLaunch");
        ActivityMonitor monitor = addMonitor(TERMINAL, null, false);
        onMain(() -> invokeStatic(launchType, "launch",
                new Class<?>[]{Activity.class, hostType, String.class, String.class}, launcher, host, "vm:pane", title));
        terminal = waitForMonitorWithTimeout(monitor, WAIT_MS);
        removeMonitor(monitor);
        require(terminal != null, "GPUI Activity did not launch");
        require(lifecycleFailure == null, "Lifecycle injection failed: " + lifecycleFailure);
        await(() -> find(terminal.getWindow().getDecorView(), SurfaceView.class) != null, "GPUI SurfaceView attachment");
        await(() -> find(terminal.getWindow().getDecorView(), SurfaceView.class).getHolder().getSurface().isValid(),
                "GPUI SurfaceView becomes drawable after Activity transition");
    }

    private void close() throws Exception {
        Activity current = terminal;
        onMain(() -> {
            try {
            Object connection = field(current, "connection");
            if (connection == null) {
                // Android can pause us while SystemUI recovers. There is then no active
                // GPUI close generation; finish the already-detached Activity as Back does.
                observations.put("Close used Activity.finish after an OS pause had detached input");
                current.finish();
            } else {
                long currentGeneration = (Long) field(connection, "generation");
                invoke(current, "gpuiClose", new Class<?>[]{long.class}, currentGeneration);
            }
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        await(current::isDestroyed, "terminal Activity destroy and launch grant release");
        terminal = null;
    }

    private void injectConnection(Activity activity, Fixture fixture) throws Exception {
        Object launch = field(activity, "launch");
        Object profile = field(field(launch, "host"), "profile");
        Class<?> clientType = targetClass("dev.herdr.android.core.CompanionClient");
        Class<?> factoryType = targetClass("dev.herdr.android.core.CompanionClient$ConnectionFactory");
        Object factory = Proxy.newProxyInstance(targetLoader, new Class<?>[]{factoryType}, (proxy, called, args) -> {
            if (called.getName().equals("open")) return fixture.open((URL) args[0]);
            if (called.getName().equals("toString")) return "Synthetic VM ConnectionFactory";
            if (called.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (called.getName().equals("equals")) return proxy == args[0];
            throw new UnsupportedOperationException(called.getName());
        });
        Constructor<?> clientConstructor = clientType.getDeclaredConstructor(profile.getClass(), factoryType);
        clientConstructor.setAccessible(true);
        Object client = clientConstructor.newInstance(profile, factory);
        AtomicLong counter = (AtomicLong) staticField(activity.getClass(), "GENERATIONS");
        long generation = counter.incrementAndGet();
        Class<?> connectionType = targetClass(TERMINAL + "$Connection");
        Constructor<?> constructor = connectionType.getDeclaredConstructor(activity.getClass(), long.class, clientType);
        constructor.setAccessible(true);
        Object connection = constructor.newInstance(activity, generation, client);
        setField(activity, "connection", connection);
        invokeStatic(activity.getClass(), "nativeSetActive", new Class<?>[]{long.class, boolean.class}, generation, false);
        invoke(activity, "publish", new Class<?>[]{connectionType, String.class}, connection, "Connecting to synthetic fixture…");
    }

    private void scheduleRealPump(Activity activity) throws Exception {
        Object connection = field(activity, "connection");
        ScheduledThreadPoolExecutor worker = (ScheduledThreadPoolExecutor) field(activity, "worker");
        ScheduledFuture<?> poll = worker.scheduleWithFixedDelay(() -> {
            try { invoke(activity, "pump", new Class<?>[]{connection.getClass()}, connection); }
            catch (Throwable failure) { lifecycleFailure = unwrap(failure); }
        }, 0, 50, TimeUnit.MILLISECONDS);
        setField(connection, "poll", poll);
    }

    private void capture(String name) throws Exception { capture(name, null); }

    private void captureDifferent(String name, long previousHash) throws Exception { capture(name, previousHash); }

    private void capture(String name, Long previousHash) throws Exception {
        android.util.Log.i("HerdrVmTests", "Capturing " + name);
        onMain(() -> { });
        SystemClock.sleep(250);
        Activity current = terminal;
        SurfaceView surface = find(current.getWindow().getDecorView(), SurfaceView.class);
        require(surface != null && surface.getWidth() > 1 && surface.getHeight() > 1, "No drawable surface for " + name);
        Bitmap bitmap = Bitmap.createBitmap(surface.getWidth(), surface.getHeight(), Bitmap.Config.ARGB_8888);
        long frameDeadline = SystemClock.elapsedRealtime() + WAIT_MS;
        int lastStatus = -1;
        boolean painted = false;
        while (SystemClock.elapsedRealtime() < frameDeadline) {
            CountDownLatch copied = new CountDownLatch(1);
            int[] outcome = {-1};
            onMain(() -> PixelCopy.request(surface, bitmap,
                    status -> { outcome[0] = status; copied.countDown(); }, new Handler(Looper.getMainLooper())));
            require(copied.await(WAIT_MS, TimeUnit.MILLISECONDS), "PixelCopy callback timed out for " + name);
            lastStatus = outcome[0];
            if (lastStatus == PixelCopy.SUCCESS) {
                int first = bitmap.getPixel(0, 0), differing = 0;
                for (int y = 0; y < bitmap.getHeight(); y += 3) {
                    for (int x = 0; x < bitmap.getWidth(); x += 3) if (bitmap.getPixel(x, y) != first) differing++;
                }
                if (differing > 300) {
                    long hash = frameHash(bitmap);
                    if (previousHash == null || hash != previousHash) { lastFrameHash = hash; painted = true; break; }
                }
            }
            // TCG/SwiftShader can need seconds before the first submitted frame.
            SystemClock.sleep(250);
        }
        require(painted, "No non-uniform GPUI frame for " + name + "; PixelCopy status=" + lastStatus);
        measureFrame(bitmap, name);
        saveBitmap(bitmap, name + "-gpui.png");
        // draw() captures Java controls; PixelCopy supplies SurfaceView contents without changing FLAG_SECURE.
        final Bitmap[] composite = new Bitmap[1];
        onMain(() -> {
            View decor = current.getWindow().getDecorView();
            composite[0] = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(composite[0]);
            decor.draw(canvas);
            int[] location = new int[2], origin = new int[2];
            surface.getLocationInWindow(location);
            decor.getLocationInWindow(origin);
            canvas.drawBitmap(bitmap, location[0] - origin[0], location[1] - origin[1], null);
        });
        saveBitmap(composite[0], name + "-app.png");
        bitmap.recycle(); composite[0].recycle();
    }

    private long frameHash(Bitmap bitmap) {
        long hash = 0xcbf29ce484222325L;
        for (int y = 0; y < bitmap.getHeight(); y += 2) {
            for (int x = 0; x < bitmap.getWidth(); x += 2) {
                hash = (hash ^ bitmap.getPixel(x, y)) * 0x100000001b3L;
            }
        }
        return hash;
    }

    private void measureFrame(Bitmap bitmap, String name) throws Exception {
        int firstBorder = -1, lastBorder = -1;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            int border = 0, sampled = 0;
            for (int x = 2; x < bitmap.getWidth() - 2; x += 8) {
                sampled++;
                int color = bitmap.getPixel(x, y);
                if (Math.abs(((color >> 16) & 255) - 0x2b) <= 3
                        && Math.abs(((color >> 8) & 255) - 0x37) <= 3
                        && Math.abs((color & 255) - 0x47) <= 3) border++;
            }
            if (sampled > 0 && border > sampled * 0.8) {
                if (firstBorder < 0) firstBorder = y;
                lastBorder = y;
            }
        }
        lastHeaderBottom = firstBorder;
        lastTerminalInkHeight = 0;
        if (firstBorder >= 0 && lastBorder > firstBorder) {
            int start = -1;
            for (int y = firstBorder + 3; y < lastBorder; y++) {
                int ink = 0;
                for (int x = 0; x < bitmap.getWidth(); x++) {
                    int pixel = bitmap.getPixel(x, y);
                    int r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
                    if (r > 110 && g >= r && b >= g) ink++;
                }
                if (ink > 5) { if (start < 0) start = y; }
                else if (start >= 0) { lastTerminalInkHeight = y - start; break; }
            }
        }
        observations.put(new JSONObject().put("frame", name).put("width_px", bitmap.getWidth())
                .put("height_px", bitmap.getHeight()).put("pixel_hash", Long.toHexString(lastFrameHash))
                .put("header_bottom_px", lastHeaderBottom).put("footer_top_px", lastBorder)
                .put("first_terminal_ink_height_px", lastTerminalInkHeight));
    }

    private void saveBitmap(Bitmap bitmap, String name) throws IOException {
        try (OutputStream stream = new FileOutputStream(new File(output, name))) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("PNG encoding failed");
        }
    }

    private void writeReport(Throwable failure) throws Exception {
        if (output == null) return;
        JSONObject report = new JSONObject();
        report.put("run_id", runId);
        report.put("target_package", targetPackage);
        report.put("suite", frameOnly ? "frame-only" : layoutOnly ? "layout-only"
                : quickCommandsOnly ? "quick-commands-runtime" : "full-runtime");
        report.put("android_api", Build.VERSION.SDK_INT);
        report.put("observations", observations);
        report.put("result", failure == null ? "PASS" : "FAIL");
        report.put("failure", failure == null ? JSONObject.NULL : failure.toString());
        report.put("passed", new JSONArray(passes));
        report.put("skipped", new JSONArray(skips));
        report.put("transport", "Synthetic in-process HttpURLConnection; no actual network, TLS handshake or Herdr server");
        report.put("renderer", "Unmodified target APK native GPUI/wgpu SurfaceView");
        report.put("physical_input", "Android KeyEvent dispatch using an enumerated non-virtual emulator keyboard and its actual KeyCharacterMap; not Titan hardware or firmware verification");
        report.put("ime_input", "Actual app InputConnection calls; not a third-party keyboard UI automation test");
        report.put("screen_capture", "PixelCopy of app-owned GPUI SurfaceView; composite adds Java controls; FLAG_SECURE retained");
        JSONArray all = new JSONArray();
        for (Fixture fixture : fixtures) {
            JSONObject item = new JSONObject().put("mode", fixture.mode);
            JSONArray requests = new JSONArray();
            for (Request request : fixture.requests()) requests.put(new JSONObject()
                    .put("method", request.method).put("path", request.path).put("body", request.body));
            all.put(item.put("requests", requests));
        }
        report.put("fixtures", all);
        try (OutputStream stream = new FileOutputStream(new File(output, "report.json"))) {
            stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private void requirePost(Fixture fixture, int index, String type, String value) throws Exception {
        Request request = fixture.posts().get(index);
        require(request.path.equals("/fixture/v1/panes/vm%3Apane/" + type), "Wrong command route: " + request.path);
        JSONObject body = new JSONObject(request.body);
        if (type.equals("text")) require(value.equals(body.getString("text")), "Text mismatch: " + request.body);
        else require(body.getJSONArray("keys").length() == 1 && value.equals(body.getJSONArray("keys").getString(0)),
                "Keys mismatch: " + request.body);
    }

    /** Preserve test assertion failures without turning them into artificial target-process crashes. */
    private void onMain(Runnable task) {
        Throwable[] failed = new Throwable[1];
        CountDownLatch completed = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try { task.run(); }
            catch (Throwable failure) { failed[0] = failure; }
            finally { completed.countDown(); }
        });
        try {
            if (!completed.await(WAIT_MS, TimeUnit.MILLISECONDS))
                throw new AssertionError("Android UI step timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Android UI step interrupted", interrupted);
        }
        if (failed[0] != null) throw new IllegalStateException("Main-thread test step failed", failed[0]);
    }

    private void tap(View view, float x, float y) { onMain(() -> dispatchTap(view, x, y)); }

    private static void dispatchTap(View view, float x, float y) {
        long at = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(at, at + 30, MotionEvent.ACTION_UP, x, y, 0);
        view.dispatchTouchEvent(down); view.dispatchTouchEvent(up);
        down.recycle(); up.recycle();
    }

    private static void physicalAltSymbol(Activity activity, int device, int code, int unicode, boolean synthetic) {
        if (!synthetic) { physical(activity, device, code, KeyEvent.META_ALT_ON); return; }
        long at = SystemClock.uptimeMillis();
        activity.dispatchKeyEvent(new KeyEvent(at, at, KeyEvent.ACTION_DOWN, code, 0, KeyEvent.META_ALT_ON,
                device, 0, 0, InputDevice.SOURCE_KEYBOARD) {
            @Override public int getUnicodeChar(int meta) {
                return (meta & KeyEvent.META_ALT_ON) != 0 ? unicode : super.getUnicodeChar(meta);
            }
        });
        activity.dispatchKeyEvent(new KeyEvent(at, at + 1, KeyEvent.ACTION_UP, code, 0, KeyEvent.META_ALT_ON,
                device, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    private static void physical(Activity activity, int device, int code, int modifiers) {
        long at = SystemClock.uptimeMillis();
        activity.dispatchKeyEvent(new KeyEvent(at, at, KeyEvent.ACTION_DOWN, code, 0, modifiers,
                device, 0, 0, InputDevice.SOURCE_KEYBOARD));
        activity.dispatchKeyEvent(new KeyEvent(at, at + 1, KeyEvent.ACTION_UP, code, 0, modifiers,
                device, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    private int findPhysicalKeyboard() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && !device.isVirtual() && device.supportsSource(InputDevice.SOURCE_KEYBOARD)
                    && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return id;
        }
        return -1;
    }

    private static float density(Activity activity) { return activity.getResources().getDisplayMetrics().density; }
    private static Button button(Activity activity, String label) {
        Button result = findButton(activity.getWindow().getDecorView(), label);
        requireUnchecked(result != null, "Missing shortcut button: " + label);
        return result;
    }
    private static Button findButton(View view, String label) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(label)) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = findButton(((ViewGroup) view).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }
    private static <T> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            T found = find(((ViewGroup) view).getChildAt(i), type);
            if (found != null) return found;
        }
        return null;
    }
    private boolean ready(Activity activity) throws Exception {
        Object connection = field(activity, "connection");
        return connection != null && (Boolean) field(connection, "ready") && !clientClosed(activity);
    }
    private boolean clientClosed(Activity activity) throws Exception {
        Object connection = field(activity, "connection");
        return connection != null && (Boolean) method(field(connection, "client").getClass(), "isClosed")
                .invoke(field(connection, "client"));
    }
    private long generation(Activity activity) throws Exception { return (Long) field(field(activity, "connection"), "generation"); }
    private void pass(String message) {
        passes.add(message);
        Bundle progress = new Bundle(); progress.putString("stream", "PASS: " + message + "\n"); sendStatus(0, progress);
    }
    private interface Check { boolean get() throws Exception; }
    private void await(Check check, String what) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + WAIT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (lifecycleFailure != null) throw new AssertionError("Lifecycle/worker failure", lifecycleFailure);
            if (check.get()) return;
            SystemClock.sleep(50);
        }
        throw new AssertionError("Timed out: " + what);
    }
    private Class<?> targetClass(String name) throws ClassNotFoundException { return Class.forName(name, true, targetLoader); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void requireUnchecked(boolean condition, String message) { require(condition, message); }
    private static Throwable unwrap(Throwable error) {
        while (error instanceof InvocationTargetException && ((InvocationTargetException) error).getCause() != null)
            error = ((InvocationTargetException) error).getCause();
        return error;
    }
    private static Field findField(Class<?> type, String name) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field field = current.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static Object field(Object object, String name) throws Exception { return findField(object.getClass(), name).get(object); }
    private static Object staticField(Class<?> type, String name) throws Exception { return findField(type, name).get(null); }
    private static void setField(Object object, String name, Object value) throws Exception { findField(object.getClass(), name).set(object, value); }
    private static Method method(Class<?> type, String name, Class<?>... types) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Method method = current.getDeclaredMethod(name, types); method.setAccessible(true); return method; }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(name);
    }
    private static Object invoke(Object object, String name, Class<?>[] types, Object... args) {
        try { return method(object.getClass(), name, types).invoke(object, args); }
        catch (Throwable failure) { throw new IllegalStateException(name, unwrap(failure)); }
    }
    private static Object invokeStatic(Class<?> type, String name, Class<?>[] types, Object... args) {
        try { return method(type, name, types).invoke(null, args); }
        catch (Throwable failure) { throw new IllegalStateException(name, unwrap(failure)); }
    }

    private static final class Request {
        final String method, path, body;
        Request(String method, String path, String body) { this.method = method; this.path = path; this.body = body; }
    }
    private static final class Fixture {
        final String mode;
        private final List<Request> requests = new ArrayList<>();
        volatile Runnable afterTextResponse;
        Fixture(String mode) { this.mode = mode; }
        synchronized List<Request> requests() { return new ArrayList<>(requests); }
        synchronized List<Request> posts() {
            List<Request> posts = new ArrayList<>();
            for (Request request : requests) if (request.method.equals("POST")) posts.add(request);
            return posts;
        }
        HttpURLConnection open(URL url) {
            require(url.getProtocol().equals("https") && url.getHost().equals("synthetic.invalid")
                    && url.getPath().startsWith("/fixture/v1/panes/vm%3Apane/"), "Unexpected test request origin/path");
            return new HttpURLConnection(url) {
                final ByteArrayOutputStream body = new ByteArrayOutputStream();
                boolean recorded;
                @Override public void connect() { }
                @Override public void disconnect() {
                    if (recorded && getRequestMethod().equals("POST") && url.getPath().endsWith("/text")) {
                        Runnable callback = afterTextResponse;
                        afterTextResponse = null;
                        if (callback != null) callback.run();
                    }
                }
                @Override public boolean usingProxy() { return false; }
                @Override public OutputStream getOutputStream() { return body; }
                @Override public int getResponseCode() throws IOException {
                    if (!recorded) {
                        synchronized (Fixture.this) { requests.add(new Request(getRequestMethod(), url.getPath(),
                                new String(body.toByteArray(), StandardCharsets.UTF_8))); }
                        recorded = true;
                    }
                    if (mode.equals("timeout")) throw new SocketTimeoutException("synthetic test timeout");
                    return mode.equals("http401") ? 401 : 200;
                }
                @Override public long getContentLengthLong() { return -1; }
                @Override public InputStream getInputStream() throws IOException {
                    String response;
                    if (getRequestMethod().equals("POST")) response = mode.equals("bad-ack")
                            || (mode.equals("bad-text-ack") && url.getPath().endsWith("/text"))
                            ? "{\"ok\":false}" : "{\"ok\":true}";
                    else if (mode.equals("malformed")) response = "{\"text\":17}";
                    else {
                        try {
                            String screen = SYNTHETIC_SCREEN;
                            if (mode.equals("long-output")) {
                                StringBuilder lines = new StringBuilder();
                                for (int n = 1; n <= 60; n++) lines.append("history row ").append(n).append('\n');
                                screen = lines.append("TAIL-FOLLOW-SENTINEL").toString();
                            }
                            response = new JSONObject().put("text", screen).toString();
                        }
                        catch (Exception error) { throw new IOException(error); }
                    }
                    return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));
                }
            };
        }
    }
}
