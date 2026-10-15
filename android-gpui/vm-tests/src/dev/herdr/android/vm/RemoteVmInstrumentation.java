package dev.herdr.android.vm;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
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
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual JNI/TCP/SSH and GPUI; the loopback peer implements a SYNTHETIC Herdr protocol. */
public final class RemoteVmInstrumentation extends Instrumentation {
    private static final String HOSTS = "dev.herdr.android.RemoteHostsActivity";
    private static final String TERMINAL = "dev.herdr.android.GpuiTerminalActivity";
    private static final String PROFILE = "SYNTHETIC SSH VM";
    private static final String PANE = "w1:p1";
    private static final String TITLE = "SYNTHETIC SSH FIXTURE";
    private static final long WAIT_MS = 120000;
    private final List<String> passes = new ArrayList<>(), skips = new ArrayList<>();
    private final List<Long> nativeHandles = new ArrayList<>();
    private final List<Activity> hostActivities = new ArrayList<>();
    private final JSONArray observations = new JSONArray();
    private String targetPackage, runId, fingerprint, fixtureHost, fixtureRoute;
    private int port;
    private JSONObject config;
    private File output;
    private ClassLoader loader;
    private Class<?> nativeType;
    private Activity hosts, terminal;
    private Object store;
    private List<?> originalProfiles;
    private boolean storeReplaced;
    private boolean frameOnly;

    @Override public void onCreate(Bundle args) {
        runId = args == null ? null : args.getString("run_id");
        if (runId == null) runId = "remote-" + System.currentTimeMillis();
        require(runId.matches("[A-Za-z0-9_-]{1,80}"), "Invalid run ID");
        require(args != null, "Explicit synthetic fixture arguments required");
        frameOnly = "true".equals(args.getString("frame_only"));
        fixtureRoute = args.getString("fixture_route", "host");
        fixtureHost = args.getString("fixture_host", "");
        require(("host".equals(fixtureRoute) && "10.0.2.2".equals(fixtureHost))
                || ("guest".equals(fixtureRoute) && "127.0.0.1".equals(fixtureHost)),
                "Fixture route must be host/10.0.2.2 or explicitly guest/127.0.0.1");
        require("synthetic-fixture".equals(args.getString("fixture_username"))
                && "public-synthetic-test-password".equals(args.getString("fixture_password")),
                "Only the checked-in fixture's public test credentials are accepted");
        port = Integer.parseInt(args.getString("fixture_port", "0"));
        require(port > 0 && port <= 65535, "A valid explicit fixture port is required");
        fingerprint = args.getString("fixture_fingerprint", "");
        require(fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}=?"), "Explicit fixture fingerprint required");
        start();
    }

    @Override public void onStart() {
        Throwable failure = null;
        Bundle result = new Bundle();
        try {
            loader = getTargetContext().getClassLoader();
            targetPackage = getTargetContext().getPackageName();
            output = new File(getTargetContext().getFilesDir(), "remote-vm-tests/" + runId);
            require(output.isDirectory() || output.mkdirs(), "Cannot create private evidence directory");
            nativeType = type("dev.herdr.android.RemoteNative");
            invokeStatic(nativeType, "ensureLoaded", new Class<?>[]{Context.class}, getTargetContext());
            config = new JSONObject().put("transport", "tcp").put("host", fixtureHost).put("port", port)
                    .put("endpoint_id", "").put("username", "synthetic-fixture").put("session", "android-test")
                    .put("expected_fingerprint", fingerprint);
            if (frameOnly) {
                skips.add("Frame-only mode: direct JNI negative/positive boundary suite was not run");
                skips.add("Frame-only mode: physical/IME/palette input and lifecycle/reconnect suite was not run");
            } else checkNativeBoundary();
            prepareStore();
            checkRemoteUi();
        } catch (Throwable problem) {
            failure = unwrap(problem);
            android.util.Log.e("HerdrRemoteVmTests", "Remote VM failure", failure);
            try { if (terminal != null && !terminal.isDestroyed()) capture("failure"); }
            catch (Throwable ignored) { }
        } finally {
            try { closeActivities(); } catch (Throwable problem) { if (failure == null) failure = unwrap(problem); }
            for (Long handle : nativeHandles) try { nativeClose(handle); } catch (Throwable ignored) { }
            try { restoreStore(); } catch (Throwable problem) { if (failure == null) failure = unwrap(problem); }
            try { writeReport(failure); } catch (Throwable problem) { if (failure == null) failure = unwrap(problem); }
            result.putString("stream", "\n" + (failure == null ? "PASS" : "FAIL: " + failure)
                    + "\nSuite: " + (frameOnly ? "remote-ssh-frames" : "remote-ssh-runtime")
                    + "\nPassed: " + passes.size() + "\nSkipped: " + skips
                    + "\nActual JNI + " + fixtureRoute + " loopback TCP/SSH at " + fixtureHost
                    + "; synthetic Herdr protocol, not a real daemon.\n");
            result.putString("result", failure == null ? "PASS" : "FAIL");
            finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
        }
    }

    private void checkNativeBoundary() throws Exception {
        JSONObject inspected = nativeJson("inspect", new Class<?>[]{String.class}, config.toString());
        require(fingerprint.equals(inspected.getString("fingerprint")), "SSH host fingerprint differs from fixture metadata");
        pass("Actual JNI and TCP SSH handshake inspect the independently supplied loopback host fingerprint");
        JSONObject missing = new JSONObject(config.toString()).put("expected_fingerprint", "");
        rejectConnection(missing, "public-synthetic-test-password", "missing trust", "trust", "fingerprint", "host key");
        JSONObject changed = new JSONObject(config.toString()).put("expected_fingerprint",
                fingerprint.equals("SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                        ? "SHA256:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" : "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        rejectConnection(changed, "public-synthetic-test-password", "changed host key", "fingerprint", "host key");
        rejectConnection(config, "intentionally-wrong-public-fixture-password", "wrong password", "auth", "password");
        long handle = nativeConnect(config, "public-synthetic-test-password");
        await(() -> "ready".equals(nativePoll(handle).optString("state")), "compatible probe and Welcome");
        JSONObject snapshot = nativePoll(handle);
        require(!snapshot.optString("boot_id").isEmpty(), "Welcome has no boot ID");
        JSONArray panes = snapshot.getJSONArray("panes");
        require(panes.length() == 1 && PANE.equals(panes.getJSONObject(0).getString("id")), "Unexpected synthetic panes");
        require(nativeJson("focus", new Class<?>[]{long.class, String.class}, handle, PANE).getBoolean("queued"), "Focus not queued");
        await(() -> PANE.equals(nativePoll(handle).optString("pane_id"))
                && nativePoll(handle).optString("text").contains(TITLE), "native focus and typed surface frame");
        observations.put(new JSONObject().put("native_ready", nativePoll(handle)));
        pass("Actual SSH authentication, compatible generation probe, Welcome, pane focus and terminal surface decode succeed");
        nativeClose(handle);
        require(nativeClosed(handle), "Closed native handle was retained");
        boolean inputRejected = false;
        try {
            nativeJson("input", new Class<?>[]{long.class, String.class, String.class}, handle, PANE,
                    new JSONObject().put("type", "text").put("text", "CLOSED_HANDLE_MUST_NOT_SEND").toString());
        } catch (IllegalStateException closed) { inputRejected = isClosedFailure(closed); }
        require(inputRejected, "Closed handle accepted input or failed for another reason");
        pass("Closing an actual native session invalidates its handle and rejects subsequent input");
    }

    private void rejectConnection(JSONObject settings, String password, String label, String... words) throws Exception {
        long handle = 0;
        try {
            handle = nativeConnect(settings, password);
            final long current = handle;
            await(() -> "closed".equals(nativePoll(current).optString("state")), label + " rejection");
            JSONObject state = nativePoll(current);
            String status = state.optString("status").toLowerCase(java.util.Locale.ROOT);
            boolean relevant = false;
            for (String word : words) if (status.contains(word)) relevant = true;
            require(relevant, "Unexpected reason for " + label + ": " + status);
            require(state.optString("text").isEmpty(), "Rejected session exposed a terminal frame");
            observations.put(new JSONObject().put("rejection", label).put("status", state.optString("status")));
        } catch (IllegalStateException failure) {
            String message = failure.toString() + " " + (failure.getCause() == null ? "" : failure.getCause().toString());
            boolean relevant = false;
            for (String word : words) if (message.toLowerCase(java.util.Locale.ROOT).contains(word)) relevant = true;
            require(relevant, "Unexpected synchronous rejection for " + label + ": " + message);
        } finally { if (handle != 0) nativeClose(handle); }
        pass("Actual JNI/SSH fails closed for " + label);
    }

    private void prepareStore() throws Exception {
        Constructor<?> ctor = type("dev.herdr.android.RemoteHostStore").getDeclaredConstructor(Context.class);
        ctor.setAccessible(true); store = ctor.newInstance(getTargetContext());
        originalProfiles = new ArrayList<>((List<?>) invoke(store, "read", new Class<?>[0]));
        // No original profile is selected or connected. Restore all values in finally.
        invoke(store, "writeLocked", new Class<?>[]{List.class}, new ArrayList<Object>());
        storeReplaced = true;
    }

    private void checkRemoteUi() throws Exception {
        hosts = startActivitySync(new Intent().setClassName(targetPackage, HOSTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        hostActivities.add(hosts);
        await(() -> findButton(hosts.getWindow().getDecorView(), "Add remote host") != null, "Remote hosts screen");
        secure(hosts.getWindow());
        onMain(() -> button(hosts, "Add remote host").performClick());
        AlertDialog editor = awaitHostDialog();
        secure(editor.getWindow());
        onMain(() -> {
            edit(editor, "Profile name").setText(PROFILE);
            edit(editor, "SSH hostname or IP").setText(fixtureHost);
            edit(editor, "SSH port").setText(Integer.toString(port));
            edit(editor, "SSH username").setText("synthetic-fixture");
            edit(editor, "Herdr session").setText("android-test");
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
        await(() -> findButton(hosts.getWindow().getDecorView(), "Connect") != null, "saved synthetic profile");
        pass("Remote hosts native widgets create an encrypted synthetic profile with explicit loopback address");
        onMain(() -> button(hosts, "Connect").performClick());
        AlertDialog trust = awaitHostDialog();
        await(() -> readOnMain(() -> "Trust fingerprint".contentEquals(trust.getButton(AlertDialog.BUTTON_POSITIVE).getText())), "SSH trust UI");
        require(readOnMain(() -> allText(dialogDecor(trust)).contains(fingerprint)), "Trust dialog omitted supplied fingerprint");
        require(readOnMain(() -> find(dialogDecor(trust), EditText.class) == null), "Password requested before trust");
        draw(dialogDecor(trust), "01-host-trust.png");
        onMain(() -> trust.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        await(() -> findButton(hosts.getWindow().getDecorView(), "Connect") != null, "cancelled trust returns to profiles");
        require(((List<?>) invoke(store, "read", new Class<?>[0])).size() == 1, "Trust cancellation lost profile");
        Object saved = ((List<?>) invoke(store, "read", new Class<?>[0])).get(0);
        require("".equals(field(saved, "expectedFingerprint")), "Cancel unexpectedly saved trust");
        pass("SSH trust dialog shows the real fingerprint and Cancel leaves the synthetic profile untrusted");
        onMain(() -> button(hosts, "Connect").performClick());
        AlertDialog acceptedTrust = awaitHostDialog();
        await(() -> readOnMain(() -> "Trust fingerprint".contentEquals(acceptedTrust.getButton(AlertDialog.BUTTON_POSITIVE).getText())), "second trust UI");
        onMain(() -> acceptedTrust.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        AlertDialog login = awaitDialog(hosts, "dialog", acceptedTrust, true,
                "password dialog after explicit fixture trust");
        secure(login.getWindow());
        EditText password = edit(login, "SSH password");
        onMain(() -> password.setText("public-synthetic-test-password"));
        onMain(() -> login.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        require(readOnMain(() -> password.length() == 0), "Login field not cleared on connect");
        await(() -> findButton(hosts.getWindow().getDecorView(), TITLE) != null, "remote pane list");
        require(field(hosts, "pendingSecret") == null && field(hosts, "password") == null, "Login retained credential fields");
        draw(hosts.getWindow().getDecorView(), "02-pane-picker.png");
        pass("Explicit fingerprint trust and password login produce the actual SSH pane list; login fields are cleared");
        ActivityMonitor monitor = addMonitor(TERMINAL, null, false);
        onMain(() -> button(hosts, TITLE).performClick());
        terminal = waitForMonitorWithTimeout(monitor, WAIT_MS); removeMonitor(monitor);
        require(terminal != null, "Remote pane did not open the GPUI terminal");
        await(() -> ready() && text().contains(TITLE), "GPUI connection frame");
        secure(terminal.getWindow());
        require((Long) field(field(terminal, "connection"), "remoteHandle") > 0, "Terminal did not own a native remote handle");
        require(field(field(terminal, "connection"), "client") == null, "Terminal unexpectedly used HTTP companion");
        capture("03-remote-connected");
        pass("Pane-selection widgets launch the actual remote GPUI Activity, own its JNI session and paint a secure native surface");
        if (frameOnly) captureRemoteFrames();
        else checkInputAndLifecycle();
    }

    private void captureRemoteFrames() throws Exception {
        String before = text();
        require(!before.contains("Text: ") && !before.contains("Key: "), "Frame-only bridge already contains terminal input");
        AlertDialog palette = openPalette();
        draw(dialogDecor(palette), "04-remote-palette.png");
        AlertDialog command = preview(palette, "Context");
        require(readOnMain(() -> !command.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled()),
                "Send + Enter enabled without user confirmation");
        draw(dialogDecor(command), "05-remote-command-preview.png");
        onMain(() -> command.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        await(() -> terminalDialog() == null, "frame-only command preview cancellation");
        require(ready() && before.equals(text()), "Opening or cancelling command preview changed the terminal frame");
        pass("Frame-only secure Cmds list and editable Context preview captured at the supplied display geometry; no terminal input sent");
    }

    private void checkInputAndLifecycle() throws Exception {
        final Activity current = terminal;
        int keyboard = findPhysicalKeyboard();
        if (keyboard >= 0) {
            onMain(() -> {
                physical(current, keyboard, KeyEvent.KEYCODE_A, 0);
                physical(current, keyboard, KeyEvent.KEYCODE_ENTER, 0);
                physical(current, keyboard, KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON);
            });
            await(() -> text().contains("Text: a") && text().contains("Key: Enter modifiers=0 Press")
                    && text().contains("Key: Char('c') modifiers=2 Press"), "physical key echoes over actual SSH");
            require(text().indexOf("Text: a") < text().indexOf("Key: Enter"), "Physical Enter overtook text");
            observations.put(new JSONObject().put("physical_keyboard_id", keyboard)
                    .put("physical_keyboard", InputDevice.getDevice(keyboard).getName()));
            pass("Enumerated emulator physical keyboard dispatch reaches JNI/native FIFO/SSH in text then Enter order, including Ctrl-C");
        } else skips.add("No non-virtual emulator alphabetic keyboard; physical-key branch was not run");
        SurfaceView surface = find(current.getWindow().getDecorView(), SurfaceView.class);
        require(surface != null, "Missing GPUI surface");
        onMain(() -> tap(surface, 48 * current.getResources().getDisplayMetrics().density,
                surface.getHeight() - 28 * current.getResources().getDisplayMetrics().density));
        await(() -> find(current.getWindow().getDecorView(), EditText.class) != null, "GPUI Keyboard hit opens Android input proxy");
        final InputConnection[] ime = new InputConnection[1];
        EditorInfo info = new EditorInfo();
        onMain(() -> ime[0] = find(current.getWindow().getDecorView(), EditText.class).onCreateInputConnection(info));
        require(ime[0] != null, "Missing real IME InputConnection");
        require((info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0, "IME personalized learning enabled");
        require((info.inputType & android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0, "IME suggestions enabled");
        onMain(() -> require(ime[0].commitText("VM_IME_α中", 1), "IME commit rejected"));
        await(() -> text().contains("Text: VM_IME_α中"), "Unicode IME echo from SSH peer");
        pass("GPUI Keyboard hit creates the real private IME connection; Unicode commit crosses actual SSH and returns in a surface frame");
        AlertDialog palette = openPalette();
        draw(dialogDecor(palette), "04-remote-palette.png");
        AlertDialog preview = preview(palette, "Context");
        onMain(() -> {
            edit(preview, "Command").setText("VM_PALETTE_INSERT");
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
        await(() -> text().contains("Text: VM_PALETTE_INSERT"), "palette Insert SSH echo");
        int entersBefore = occurrences(text(), "Key: Enter");
        AlertDialog second = preview(openPalette(), "Context");
        onMain(() -> edit(second, "Command").setText("VM_PALETTE_SUBMIT"));
        require(readOnMain(() -> !second.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled()), "Submit bypassed empty-prompt acknowledgement");
        onMain(() -> checkbox(second, "OMP is idle and its input prompt is empty").setChecked(true));
        draw(dialogDecor(second), "05-remote-command-preview.png");
        onMain(() -> second.getButton(AlertDialog.BUTTON_NEUTRAL).performClick());
        await(() -> text().contains("Text: VM_PALETTE_SUBMIT") && occurrences(text(), "Key: Enter") == entersBefore + 1,
                "palette Submit text and one Enter SSH echo");
        String after = text();
        require(after.lastIndexOf("Text: VM_PALETTE_SUBMIT") < after.lastIndexOf("Key: Enter"), "Submit Enter preceded text");
        require(occurrences(after, "Text: VM_PALETTE_INSERT") == 1 && occurrences(after, "Text: VM_PALETTE_SUBMIT") == 1,
                "Palette replayed a command");
        capture("06-input-round-trip");
        pass("Remote palette Insert and user-confirmed Send + Enter cross SSH exactly once, preserving text-before-Enter order");
        AlertDialog stale = preview(openPalette(), "Context");
        onMain(() -> edit(stale, "Command").setText("STALE_PALETTE_MUST_NOT_SEND"));
        final long handle = (Long) field(field(current, "connection"), "remoteHandle");
        final long generation = (Long) field(field(current, "connection"), "generation");
        getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME), true);
        getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME), true);
        await(() -> field(current, "connection") == null, "HOME detaches remote session");
        await(() -> nativeClosed(handle), "HOME closes native session handle");
        onMain(() -> {
            require(!ime[0].commitText("STALE_IME_MUST_NOT_SEND", 1), "Paused IME accepted input");
            stale.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            require(!(Boolean) invokeStatic(current.getClass(), "nativeSendKey", new Class<?>[]{long.class, String.class},
                    generation, "enter"), "Paused native generation accepted input");
        });
        require(readOnMain(() -> !stale.isShowing()), "HOME retained command preview");
        wakeScreen();
        onMain(() -> hosts.startActivity(new Intent().setClassName(targetPackage, TERMINAL)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        await(current::isDestroyed, "resuming consumed remote session requires explicit reconnect");
        terminal = null;
        pass("Actual HOME closes SSH and dismisses command preview; stale IME/palette/native-generation input is rejected and resume cannot reconnect silently");
        reopenHostsAfterHome(handle);
        // The fixture starts every authenticated bridge with a fresh echo log.
        // Reopening through UI verifies no input in either native FIFO is replayed.
        onMain(() -> button(hosts, "Connect").performClick());
        AlertDialog login = awaitDialog(hosts, "dialog", null, true, "trusted explicit reconnect login");
        onMain(() -> {
            edit(login, "SSH password").setText("public-synthetic-test-password");
            login.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
        await(() -> findButton(hosts.getWindow().getDecorView(), TITLE) != null, "reconnect pane picker");
        ActivityMonitor monitor = addMonitor(TERMINAL, null, false);
        onMain(() -> button(hosts, TITLE).performClick());
        terminal = waitForMonitorWithTimeout(monitor, WAIT_MS); removeMonitor(monitor);
        require(terminal != null, "Explicit reconnect did not launch terminal");
        await(() -> ready() && text().contains(TITLE), "explicit reconnect fresh frame");
        long until = SystemClock.elapsedRealtime() + 1800;
        while (SystemClock.elapsedRealtime() < until) {
            require(ready() && !text().contains("Text: ") && !text().contains("Key: "), "Old or stale input replayed after reconnect");
            SystemClock.sleep(100);
        }
        capture("07-reconnected-no-replay");
        pass("Explicit password reconnect and pane selection produce a fresh SSH bridge frame with no old or stale commands replayed");
    }

    private void reopenHostsAfterHome(long closedHandle) throws Exception {
        // HOME can leave the previous host Activity paused even after the terminal
        // destroys itself. Model a user's explicit reopen with a fresh UI instance.
        Activity previous = hosts;
        finishHostActivity(previous);
        hosts = null;
        wakeScreen();
        hosts = startActivitySync(new Intent().setClassName(targetPackage, HOSTS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        hostActivities.add(hosts);
        require(hosts != previous, "Explicit reopen unexpectedly reused a destroyed host screen");
        awaitReopenedHostScreen();
        secure(hosts.getWindow());
        require(allText(hosts.getWindow().getDecorView()).contains(PROFILE)
                && allText(hosts.getWindow().getDecorView()).contains(fingerprint), "Explicit reopen lost the saved synthetic profile or SSH trust");
        require((Long) field(hosts, "ownedHandle") == 0 && hostDialog() == null && nativeClosed(closedHandle),
                "Explicit reopen automatically connected or recovered the closed SSH session");
        pass("Explicit app reopen creates a fresh host screen with saved SSH trust and no automatic connection");
    }

    private void wakeScreen() {
        KeyguardState before = keyguardState();
        requireNoCredentialLock(before);
        getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_WAKEUP), true);
        getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_WAKEUP), true);
        KeyguardState afterWake = keyguardState();
        requireNoCredentialLock(afterWake);
        if (afterWake.locked) dismissNonsecureKeyguard(5000);
    }

    private void awaitReopenedHostScreen() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + WAIT_MS;
        long nextRecovery = 0;
        int attempts = 0;
        KeyguardState state = keyguardState();
        recordKeyguard("reopen-wait", attempts, state);
        while (SystemClock.elapsedRealtime() < deadline) {
            state = keyguardState();
            requireNoCredentialLock(state);
            boolean visible = !hosts.isDestroyed() && (Boolean) field(hosts, "visible");
            if (!state.locked && visible && findButton(hosts.getWindow().getDecorView(), "Connect") != null) {
                recordKeyguard("reopen-ready", attempts, state);
                return;
            }
            require(!hosts.isDestroyed(), "Fresh host Activity was destroyed while waiting for explicit reopen");
            long now = SystemClock.elapsedRealtime();
            if (state.locked && now >= nextRecovery) {
                attempts++;
                recordKeyguard("observed-nonsecure-lock", attempts, state);
                // SystemUI can recreate its ordinary swipe keyguard after the first
                // wake. Retry only when Android reports that actual nonsecure lock.
                getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_WAKEUP), true);
                getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_WAKEUP), true);
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                try { dismissNonsecureKeyguard(Math.min(5000, remaining)); }
                catch (IllegalStateException unavailable) {
                    observations.put(new JSONObject().put("keyguard_recovery", "ordinary-dismiss-unavailable")
                            .put("attempt", attempts).put("reason", unavailable.toString()));
                }
                nextRecovery = SystemClock.elapsedRealtime() + 5000;
            }
            SystemClock.sleep(100);
        }
        state = keyguardState();
        recordKeyguard("reopen-blocked", attempts, state);
        throw new AssertionError("Explicit reopen blocked after bounded ordinary wake/unlock: " + state
                + ", host_visible=" + field(hosts, "visible") + ", recovery_attempts=" + attempts
                + "; no visible saved-profile screen. Check SystemUI/keyguard diagnostics");
    }

    private void dismissNonsecureKeyguard(long timeoutMs) {
        KeyguardState state = keyguardState();
        requireNoCredentialLock(state);
        if (!state.locked) return;
        // Ordinary emulator unlock only; no credential, security, or timeout setting changes.
        try (InputStream command = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                getUiAutomation().executeShellCommand("wm dismiss-keyguard"))) {
            java.util.concurrent.FutureTask<Void> drained = new java.util.concurrent.FutureTask<>(() -> {
                byte[] bytes = new byte[256];
                while (command.read(bytes) != -1) { }
                return null;
            });
            Thread reader = new Thread(drained, "remote-vm-dismiss-keyguard");
            reader.setDaemon(true); reader.start();
            drained.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception failure) { throw new IllegalStateException("Could not dismiss test-VM keyguard", failure); }
    }

    private KeyguardState keyguardState() {
        KeyguardManager manager = (KeyguardManager) getTargetContext().getSystemService(Context.KEYGUARD_SERVICE);
        PowerManager power = (PowerManager) getTargetContext().getSystemService(Context.POWER_SERVICE);
        require(manager != null && power != null, "Cannot observe Android keyguard/power state");
        return new KeyguardState(manager.isKeyguardLocked(), manager.isDeviceSecure(),
                manager.isKeyguardSecure(), power.isInteractive());
    }
    private static void requireNoCredentialLock(KeyguardState state) {
        require(!state.locked || !state.deviceSecure && !state.keyguardSecure,
                "Secure credential keyguard blocks VM test; manual unlock is required. " + state);
    }
    private void recordKeyguard(String phase, int attempt, KeyguardState state) throws Exception {
        boolean visible = hosts != null && !hosts.isDestroyed() && (Boolean) field(hosts, "visible");
        observations.put(new JSONObject().put("keyguard_recovery", phase).put("attempt", attempt)
                .put("elapsed_realtime_ms", SystemClock.elapsedRealtime()).put("keyguard_locked", state.locked)
                .put("device_secure", state.deviceSecure).put("keyguard_secure", state.keyguardSecure)
                .put("interactive", state.interactive).put("host_visible", visible));
        android.util.Log.i("HerdrRemoteVmTests", "Keyguard " + phase + " attempt=" + attempt
                + " " + state + " host_visible=" + visible);
    }
    private static final class KeyguardState {
        final boolean locked, deviceSecure, keyguardSecure, interactive;
        KeyguardState(boolean locked, boolean deviceSecure, boolean keyguardSecure, boolean interactive) {
            this.locked = locked; this.deviceSecure = deviceSecure;
            this.keyguardSecure = keyguardSecure; this.interactive = interactive;
        }
        @Override public String toString() {
            return "keyguard_locked=" + locked + ", device_secure=" + deviceSecure
                    + ", keyguard_secure=" + keyguardSecure + ", interactive=" + interactive;
        }
    }

    private AlertDialog openPalette() throws Exception {
        onMain(() -> button(terminal, "Cmds").performClick());
        AlertDialog dialog = awaitDialog(terminal, "displayDialog", null, false, "remote Cmds palette");
        require(readOnMain(() -> dialog.getListView() != null), "Expected command list");
        return dialog;
    }
    private AlertDialog preview(AlertDialog list, String label) throws Exception {
        int row = readOnMain(() -> {
            for (int i = 0; i < list.getListView().getAdapter().getCount(); i++) {
                String value = list.getListView().getAdapter().getItem(i).toString().split("\n", 2)[0].replace("★ ", "");
                if (label.equals(value)) return i;
            }
            return -1;
        });
        require(row >= 0, "Missing command " + label); final int index = row;
        onMain(() -> list.getListView().performItemClick(list.getListView().getAdapter().getView(index, null, list.getListView()),
                index, list.getListView().getAdapter().getItemId(index)));
        return awaitDialog(terminal, "displayDialog", list, true, "command preview");
    }
    private AlertDialog terminalDialog() {
        return readOnMain(() -> terminal == null ? null : (AlertDialog) field(terminal, "displayDialog"));
    }
    private AlertDialog hostDialog() { return readOnMain(() -> (AlertDialog) field(hosts, "dialog")); }
    private AlertDialog awaitHostDialog() throws Exception {
        return awaitDialog(hosts, "dialog", null, false, "remote host dialog");
    }
    private AlertDialog awaitDialog(Activity owner, String fieldName, AlertDialog previous,
            boolean needsEditor, String what) throws Exception {
        final AlertDialog[] result = new AlertDialog[1];
        await(() -> readOnMain(() -> {
            // The application publishes its dialog field before Dialog.show().
            // Read after that UI callback returns; never trigger decor creation
            // from the instrumentation thread while AlertController installs it.
            AlertDialog dialog = (AlertDialog) field(owner, fieldName);
            if (dialog == null || dialog == previous || !dialog.isShowing() || dialog.getWindow() == null) return false;
            View decor = dialog.getWindow().peekDecorView();
            if (decor == null || needsEditor && find(decor, EditText.class) == null) return false;
            secure(dialog.getWindow());
            result[0] = dialog;
            return true;
        }), what);
        return result[0];
    }
    private View dialogDecor(AlertDialog dialog) {
        return readOnMain(() -> {
            require(dialog.isShowing() && dialog.getWindow() != null, "Dialog is not fully shown");
            View decor = dialog.getWindow().peekDecorView();
            require(decor != null, "Shown dialog has no installed decor");
            return decor;
        });
    }
    private static void secure(android.view.Window window) {
        require(window != null && (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0, "FLAG_SECURE missing");
    }
    private boolean ready() throws Exception {
        if (terminal == null) return false;
        Object connection = field(terminal, "connection");
        return connection != null && (Boolean) field(connection, "ready") && !(Boolean) field(connection, "closed");
    }
    private String text() throws Exception {
        Object connection = terminal == null ? null : field(terminal, "connection");
        return connection == null ? "" : (String) field(connection, "text");
    }
    private long nativeConnect(JSONObject settings, String password) throws Exception {
        long handle = (Long) invokeStatic(nativeType, "connect", new Class<?>[]{String.class, String.class},
                settings.toString(), new JSONObject().put("password", password).toString());
        require(handle > 0, "Native connection has no handle"); nativeHandles.add(handle); return handle;
    }
    private JSONObject nativePoll(long handle) throws Exception {
        JSONObject state = nativeJson("poll", new Class<?>[]{long.class}, handle);
        require(!state.has("error"), "Native poll failed: " + state); return state;
    }
    private boolean nativeClosed(long handle) throws Exception {
        try { nativeJson("poll", new Class<?>[]{long.class}, handle); return false; }
        catch (IllegalStateException closed) {
            if (isClosedFailure(closed)) return true;
            throw closed;
        }
    }
    private static boolean isClosedFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof IllegalStateException && current.getMessage() != null
                    && current.getMessage().contains("Remote session is closed")) return true;
        }
        return false;
    }
    private JSONObject nativeJson(String name, Class<?>[] types, Object... args) throws Exception {
        return new JSONObject((String) invokeStatic(nativeType, name, types, args));
    }
    private void nativeClose(long handle) { invokeStatic(nativeType, "close", new Class<?>[]{long.class}, handle); }

    private void closeActivities() throws Exception {
        if (terminal != null && !terminal.isDestroyed()) {
            Activity current = terminal; onMain(current::finish); await(current::isDestroyed, "terminal cleanup");
        }
        for (int i = hostActivities.size() - 1; i >= 0; i--) finishHostActivity(hostActivities.get(i));
    }
    private void finishHostActivity(Activity current) throws Exception {
        ThreadPoolExecutor worker = (ThreadPoolExecutor) field(current, "worker");
        if (!current.isDestroyed()) {
            onMain(current::finish); await(current::isDestroyed, "host-screen cleanup");
        }
        require(worker.awaitTermination(WAIT_MS, TimeUnit.MILLISECONDS), "Profile worker failed to stop before restoration");
    }
    private void restoreStore() throws Exception {
        if (!storeReplaced) return;
        invoke(store, "writeLocked", new Class<?>[]{List.class}, originalProfiles);
        List<?> restored = (List<?>) invoke(store, "read", new Class<?>[0]);
        require(restored.size() == originalProfiles.size(), "Original remote profile count changed");
        for (int i = 0; i < restored.size(); i++) require(invoke(restored.get(i), "toJson", new Class<?>[0]).toString()
                .equals(invoke(originalProfiles.get(i), "toJson", new Class<?>[0]).toString()), "Original remote profile changed");
        pass("Original encrypted remote profile values restored and reread after test Activities and workers stopped");
    }
    private void capture(String name) throws Exception {
        final Activity current = terminal;
        final SurfaceView surface = find(current.getWindow().getDecorView(), SurfaceView.class);
        await(() -> surface != null && surface.getWidth() > 1 && surface.getHeight() > 1
                && surface.getHolder().getSurface().isValid(), "drawable native surface");
        secure(current.getWindow());
        onMain(() -> { });
        SystemClock.sleep(500);
        Bitmap bitmap = Bitmap.createBitmap(surface.getWidth(), surface.getHeight(), Bitmap.Config.ARGB_8888);
        boolean painted = false; int status = -1;
        long until = SystemClock.elapsedRealtime() + WAIT_MS;
        while (SystemClock.elapsedRealtime() < until) {
            CountDownLatch copied = new CountDownLatch(1); int[] outcome = {-1};
            onMain(() -> PixelCopy.request(surface, bitmap, result -> { outcome[0] = result; copied.countDown(); },
                    new Handler(Looper.getMainLooper())));
            require(copied.await(WAIT_MS, TimeUnit.MILLISECONDS), "PixelCopy timed out"); status = outcome[0];
            if (status == PixelCopy.SUCCESS) {
                int first = bitmap.getPixel(0, 0), differing = 0;
                for (int y = 0; y < bitmap.getHeight(); y += 3) for (int x = 0; x < bitmap.getWidth(); x += 3)
                    if (bitmap.getPixel(x, y) != first) differing++;
                if (differing > 300) { painted = true; break; }
            }
            SystemClock.sleep(250);
        }
        require(painted, "No non-uniform GPUI native frame; PixelCopy=" + status);
        saveBitmap(bitmap, name + "-gpui.png");
        final Bitmap[] composite = new Bitmap[1];
        onMain(() -> {
            View decor = current.getWindow().getDecorView();
            composite[0] = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(composite[0]); decor.draw(canvas);
            int[] position = new int[2], origin = new int[2]; surface.getLocationInWindow(position); decor.getLocationInWindow(origin);
            canvas.drawBitmap(bitmap, position[0] - origin[0], position[1] - origin[1], null);
        });
        saveBitmap(composite[0], name + "-app.png");
        observations.put(new JSONObject().put("frame", name).put("width", bitmap.getWidth()).put("height", bitmap.getHeight())
                .put("terminal_text", text()).put("capture", "PixelCopy plus Java controls; FLAG_SECURE unchanged"));
        bitmap.recycle(); composite[0].recycle();
    }
    private void draw(View decor, String name) throws Exception {
        await(() -> decor.getWidth() > 1 && decor.getHeight() > 1, "dialog layout");
        final Bitmap[] image = new Bitmap[1];
        onMain(() -> { image[0] = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            decor.draw(new Canvas(image[0])); });
        saveBitmap(image[0], name); image[0].recycle();
    }
    private void saveBitmap(Bitmap image, String name) throws Exception {
        try (OutputStream out = new FileOutputStream(new File(output, name))) {
            require(image.compress(Bitmap.CompressFormat.PNG, 100, out), "PNG encoding failed");
        }
    }
    private void writeReport(Throwable failure) throws Exception {
        if (output == null) return;
        JSONObject report = new JSONObject().put("run_id", runId).put("target_package", targetPackage)
                .put("suite", frameOnly ? "remote-ssh-frames" : "remote-ssh-runtime").put("android_api", Build.VERSION.SDK_INT)
                .put("result", failure == null ? "PASS" : "FAIL").put("failure", failure == null ? JSONObject.NULL : failure.toString())
                .put("passed", new JSONArray(passes)).put("skipped", new JSONArray(skips)).put("observations", observations)
                .put("transport", "Actual JNI + TCP + SSH to " + fixtureHost + " (" + fixtureRoute
                        + " loopback fixture); synthetic typed Herdr protocol; no actual Herdr daemon")
                .put("renderer", "Unmodified target APK native GPUI/wgpu SurfaceView; FLAG_SECURE retained")
                .put("input_scope", "Emulator InputDevice and app InputConnection; not Titan hardware or a third-party IME UI")
                .put("fixture_route", fixtureRoute).put("fixture_host", fixtureHost)
                .put("fixture_port", port).put("fixture_fingerprint", fingerprint);
        try (OutputStream out = new FileOutputStream(new File(output, "report.json"))) {
            out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
    private void pass(String message) {
        passes.add(message); Bundle progress = new Bundle(); progress.putString("stream", "PASS: " + message + "\n"); sendStatus(0, progress);
    }
    private interface Check { boolean get() throws Exception; }
    private void await(Check check, String what) throws Exception {
        long until = SystemClock.elapsedRealtime() + WAIT_MS;
        while (SystemClock.elapsedRealtime() < until) { if (check.get()) return; SystemClock.sleep(50); }
        throw new AssertionError("Timed out: " + what);
    }
    private void onMain(Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) { task.run(); return; }
        Throwable[] failure = new Throwable[1]; CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> { try { task.run(); } catch (Throwable problem) { failure[0] = problem; } finally { done.countDown(); } });
        try { require(done.await(WAIT_MS, TimeUnit.MILLISECONDS), "Main-thread test step timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        if (failure[0] != null) throw new IllegalStateException("Main-thread test step failed", failure[0]);
    }
    private interface UiRead<T> { T get() throws Exception; }
    @SuppressWarnings("unchecked")
    private <T> T readOnMain(UiRead<T> read) {
        Object[] result = new Object[1];
        onMain(() -> {
            try { result[0] = read.get(); }
            catch (Exception failure) { throw new IllegalStateException("UI read failed", failure); }
        });
        return (T) result[0];
    }
    private static int occurrences(String value, String needle) {
        int count = 0, at = 0; while ((at = value.indexOf(needle, at)) >= 0) { count++; at += needle.length(); } return count;
    }
    private static void tap(View view, float x, float y) {
        long at = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(at, at + 30, MotionEvent.ACTION_UP, x, y, 0);
        view.dispatchTouchEvent(down); view.dispatchTouchEvent(up); down.recycle(); up.recycle();
    }
    private static void physical(Activity activity, int device, int code, int modifiers) {
        long at = SystemClock.uptimeMillis();
        activity.dispatchKeyEvent(new KeyEvent(at, at, KeyEvent.ACTION_DOWN, code, 0, modifiers, device, 0, 0, InputDevice.SOURCE_KEYBOARD));
        activity.dispatchKeyEvent(new KeyEvent(at, at + 1, KeyEvent.ACTION_UP, code, 0, modifiers, device, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }
    private static int findPhysicalKeyboard() {
        for (int id : InputDevice.getDeviceIds()) { InputDevice device = InputDevice.getDevice(id);
            if (device != null && !device.isVirtual() && device.supportsSource(InputDevice.SOURCE_KEYBOARD)
                    && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return id; }
        return -1;
    }
    private static Button button(Activity activity, String label) {
        Button result = findButton(activity.getWindow().getDecorView(), label); require(result != null, "Missing button " + label); return result;
    }
    private static Button findButton(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button value = findButton(((ViewGroup) view).getChildAt(i), label); if (value != null) return value; }
        return null;
    }
    private static <T> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            T value = find(((ViewGroup) view).getChildAt(i), type); if (value != null) return value; }
        return null;
    }
    private static <T> void findAll(View view, Class<T> type, List<T> values) {
        if (type.isInstance(view)) values.add(type.cast(view));
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) findAll(((ViewGroup) view).getChildAt(i), type, values);
    }
    private EditText edit(AlertDialog dialog, String hint) {
        return readOnMain(() -> {
            List<EditText> fields = new ArrayList<>(); findAll(dialogDecor(dialog), EditText.class, fields);
            for (EditText value : fields) if (hint.contentEquals(value.getHint())) return value;
            throw new AssertionError("Missing editor " + hint);
        });
    }
    private CheckBox checkbox(AlertDialog dialog, String label) {
        return readOnMain(() -> {
            List<CheckBox> fields = new ArrayList<>(); findAll(dialogDecor(dialog), CheckBox.class, fields);
            for (CheckBox value : fields) if (label.contentEquals(value.getText())) return value;
            throw new AssertionError("Missing checkbox " + label);
        });
    }
    private static String allText(View view) {
        StringBuilder result = new StringBuilder(); List<TextView> values = new ArrayList<>(); findAll(view, TextView.class, values);
        for (TextView value : values) result.append(value.getText()).append('\n'); return result.toString();
    }
    private Class<?> type(String name) throws Exception { return Class.forName(name, true, loader); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof InvocationTargetException && failure.getCause() != null) failure = failure.getCause(); return failure;
    }
    private static Object field(Object value, String name) throws Exception {
        for (Class<?> current = value.getClass(); current != null; current = current.getSuperclass()) {
            try { Field field = current.getDeclaredField(name); field.setAccessible(true); return field.get(value); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static Method method(Class<?> type, String name, Class<?>[] types) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Method method = current.getDeclaredMethod(name, types); method.setAccessible(true); return method; }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(name);
    }
    private static Object invoke(Object value, String name, Class<?>[] types, Object... args) {
        try { return method(value.getClass(), name, types).invoke(value, args); }
        catch (Throwable failure) { throw new IllegalStateException(name, unwrap(failure)); }
    }
    private static Object invokeStatic(Class<?> type, String name, Class<?>[] types, Object... args) {
        try { return method(type, name, types).invoke(null, args); }
        catch (Throwable failure) { throw new IllegalStateException(name, unwrap(failure)); }
    }
}
