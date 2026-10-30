package dev.herdr.android.vm;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import org.json.JSONObject;

/** Screen-only local diagnostics checks against the separately packaged target APK. */
public final class DiagnosticsVmInstrumentation extends Instrumentation {
    private static final String ACTIVITY = "dev.herdr.android.DeviceDiagnosticsActivity";
    private static final String READ_LABEL = "Read diagnostics";
    private static final long WAIT_MS = 15000;

    private final List<String> passes = new ArrayList<>();
    private final List<String> skips = new ArrayList<>();
    private final List<Activity> createdActivities = Collections.synchronizedList(new ArrayList<>());
    private volatile Activity currentActivity;
    private volatile boolean activityResumed;
    private Application application;
    private Application.ActivityLifecycleCallbacks lifecycleCallbacks;
    private Context targetContext;
    private String targetPackage;
    private String runId;
    private File output;

    @Override public void onCreate(Bundle args) {
        runId = args == null ? null : args.getString("run_id");
        if (runId == null) runId = "manual-" + System.currentTimeMillis();
        require(runId.matches("[A-Za-z0-9_-]{1,80}"), "Invalid diagnostics run ID");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Throwable failure = null;
        try {
            targetContext = getTargetContext();
            targetPackage = targetContext.getPackageName();
            require(targetPackage.matches("[A-Za-z0-9_.]+"), "Invalid target package identity");
            output = new File(targetContext.getFilesDir(), "diagnostics-vm-tests/" + runId);
            require(output.isDirectory() || output.mkdirs(), "Cannot create app-owned diagnostics evidence directory");
            installLifecycleObserver();
            verifyNoServiceOrForegroundPermission();
            Activity screen = openScreen();
            verifyDefaultAndSecure(screen);
            verifyRead(screen);
            screen = verifyPauseAndReturn(screen);
            verifyRecreation(screen);
        } catch (Throwable problem) {
            failure = unwrap(problem);
            android.util.Log.e("HerdrDiagnosticsVmTests", "Diagnostics VM runtime failure", failure);
            try {
                Activity screen = currentActivity;
                if (screen != null && !screen.isDestroyed()) capture(screen, "failure");
            } catch (Throwable ignored) { }
        } finally {
            try { cleanup(); }
            catch (Throwable cleanupFailure) { if (failure == null) failure = unwrap(cleanupFailure); }
            try { writeReport(failure); }
            catch (Throwable reportFailure) { if (failure == null) failure = unwrap(reportFailure); }
            result.putString("stream", "\n" + (failure == null ? "PASS" : "FAIL: " + failure)
                    + "\nPassed checks: " + passes.size() + "\nSkipped checks: " + skips
                    + "\nEvidence: " + (output == null ? "unavailable" : output.getAbsolutePath())
                    + "\nLocal screen diagnostics only; no GPUI/JNI or network transport was exercised.\n");
            result.putString("result", failure == null ? "PASS" : "FAIL");
            finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
        }
    }

    private void installLifecycleObserver() {
        application = (Application) targetContext.getApplicationContext();
        lifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                if (isDiagnosticsActivity(activity)) createdActivities.add(activity);
            }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityResumed(Activity activity) {
                if (isDiagnosticsActivity(activity)) { currentActivity = activity; activityResumed = true; }
            }
            @Override public void onActivityPaused(Activity activity) {
                if (isDiagnosticsActivity(activity) && currentActivity == activity) activityResumed = false;
            }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) {
                if (isDiagnosticsActivity(activity) && currentActivity == activity) {
                    currentActivity = null;
                    activityResumed = false;
                }
            }
        };
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks);
    }

    private boolean isDiagnosticsActivity(Activity activity) {
        return ACTIVITY.equals(activity.getClass().getName());
    }

    private void verifyNoServiceOrForegroundPermission() throws Exception {
        PackageManager manager = targetContext.getPackageManager();
        PackageInfo info = manager.getPackageInfo(targetPackage,
                PackageManager.GET_SERVICES | PackageManager.GET_PERMISSIONS);
        require(info.services == null || info.services.length == 0,
                "Installed target declares one or more services");
        if (info.requestedPermissions != null) {
            for (String permission : info.requestedPermissions) {
                require(!"android.permission.FOREGROUND_SERVICE".equals(permission)
                                && !"android.permission.FOREGROUND_SERVICE_SPECIAL_USE".equals(permission),
                        "Installed target requests a foreground-service permission: " + permission);
            }
        }
        pass("Installed target declares no services and requests no foreground-service permissions");
    }

    private Activity openScreen() throws Exception {
        Intent intent = new Intent().setClassName(targetPackage, ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        onMain(() -> targetContext.startActivity(intent));
        await(() -> activityResumed && currentActivity != null, "diagnostics Activity launch");
        return currentActivity;
    }

    private void verifyDefaultAndSecure(Activity activity) throws Exception {
        await(() -> activityResumed && activity.hasWindowFocus(), "initial diagnostics screen focus");
        require(!hasDiagnosticJson(activity), "Diagnostic output was shown before tapping Read diagnostics");
        checkSecureFlag(activity);
        pass("No diagnostics output is shown before the user taps Read diagnostics");
        capture(activity, "01-before-read");
    }

    private void checkSecureFlag(Activity activity) {
        boolean secure = (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
        boolean probeException = (targetContext.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && "1".equals(Settings.Global.getString(targetContext.getContentResolver(), "herdr_probe"));
        require(secure || probeException, "Diagnostics Activity FLAG_SECURE is absent outside the debuggable herdr_probe exception");
    }

    private void verifyRead(Activity activity) throws Exception {
        onMain(() -> {
            Button button = readButton(activity);
            require(button.isEnabled(), "Read diagnostics button is disabled before reading");
            button.performClick();
        });
        await(() -> hasDiagnosticJson(activity), "diagnostics JSON output");
        JSONObject data = renderedJson(activity);
        validateDiagnosticSnapshot(data);
        await(() -> onMain(() -> readButton(activity).isEnabled()), "Read diagnostics button re-enabling");
        pass("Read diagnostics renders valid allowlisted JSON and re-enables the button");
        capture(activity, "02-read-result");
    }

    private void validateDiagnosticSnapshot(JSONObject data) throws Exception {
        require(data != null, "Diagnostics read returned no JSON object");
        List<String> expected = java.util.Arrays.asList("schema_version", "device_model", "manufacturer", "android_sdk",
                "battery_percent", "app_storage_available_bytes");
        List<String> actual = new ArrayList<>();
        for (Iterator<String> keys = data.keys(); keys.hasNext();) actual.add(keys.next());
        Collections.sort(expected);
        Collections.sort(actual);
        require(actual.equals(expected), "Diagnostics JSON contains missing or non-allowlisted fields");
        require(data.optInt("schema_version", -1) == 1, "Diagnostics schema version is not 1");
        require(data.optInt("android_sdk", -1) == Build.VERSION.SDK_INT,
                "Diagnostics SDK value is not the running Android API");
        require(Build.MODEL.equals(data.optString("device_model", ""))
                        && Build.MANUFACTURER.equals(data.optString("manufacturer", "")),
                "Diagnostics model/manufacturer do not match Android Build values");
        Object battery = data.opt("battery_percent");
        require(battery == JSONObject.NULL || battery instanceof Number
                        && ((Number) battery).intValue() >= 0 && ((Number) battery).intValue() <= 100,
                "Diagnostics battery percentage is neither null nor in [0,100]");
        Object storage = data.opt("app_storage_available_bytes");
        require(storage == JSONObject.NULL || storage instanceof Number && ((Number) storage).longValue() >= 0,
                "Diagnostics app-storage capacity is neither null nor a nonnegative byte count");
    }

    private Activity verifyPauseAndReturn(Activity activity) throws Exception {
        sendHome();
        await(() -> !activityResumed, "diagnostics Activity pause after HOME");
        await(() -> !hasDiagnosticJson(activity), "output clearing on pause");
        pass("Pausing the diagnostics screen clears the displayed output");
        Activity returned = openScreen();
        await(() -> activityResumed && returned.hasWindowFocus(), "return to diagnostics screen");
        require(!hasDiagnosticJson(returned), "Diagnostics output reappeared after returning from HOME");
        pass("Returning to diagnostics screen does not restore the cleared output");
        capture(returned, "03-returned");
        return returned;
    }

    private void verifyRecreation(Activity activity) throws Exception {
        verifyRead(activity);
        require(hasDiagnosticJson(activity), "Cannot recreate diagnostics Activity without output to clear");
        onMain(activity::recreate);
        await(() -> activityResumed && currentActivity != null && currentActivity != activity,
                "diagnostics Activity recreation");
        Activity recreated = currentActivity;
        await(() -> recreated.hasWindowFocus(), "recreated diagnostics screen focus");
        require(!hasDiagnosticJson(recreated), "Diagnostics output survived Activity recreation");
        checkSecureFlag(recreated);
        pass("Activity recreation starts with no diagnostics output");
        capture(recreated, "04-recreated");
    }

    private Button readButton(Activity activity) {
        Button found = findText(activity.getWindow().getDecorView(), Button.class, READ_LABEL);
        if (found == null) throw new IllegalStateException("Read diagnostics button is missing");
        return found;
    }

    private static <T extends TextView> T findText(View root, Class<T> type, String text) {
        if (type.isInstance(root) && text.contentEquals(((TextView) root).getText())) return type.cast(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                T found = findText(group.getChildAt(index), type, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private JSONObject renderedJson(Activity activity) {
        List<TextView> views = new ArrayList<>();
        collectTextViews(activity.getWindow().getDecorView(), views);
        for (TextView view : views) {
            String text = view.getText().toString().trim();
            if (text.startsWith("{")) {
                try { return new JSONObject(text); }
                catch (Exception ignored) { }
            }
        }
        return null;
    }

    private boolean hasDiagnosticJson(Activity activity) { return renderedJson(activity) != null; }

    private static void collectTextViews(View root, List<TextView> result) {
        if (root instanceof TextView) result.add((TextView) root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) collectTextViews(group.getChildAt(index), result);
        }
    }

    private void capture(Activity activity, String name) throws Exception {
        final Bitmap[] image = new Bitmap[1];
        onMain(() -> {
            checkSecureFlag(activity);
            View decor = activity.getWindow().getDecorView();
            require(decor.getWidth() > 0 && decor.getHeight() > 0, "Diagnostics Activity has no drawable view bounds");
            image[0] = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
            decor.draw(new Canvas(image[0]));
        });
        try (FileOutputStream stream = new FileOutputStream(new File(output, name + ".png"))) {
            require(image[0].compress(Bitmap.CompressFormat.PNG, 100, stream), "Diagnostics View.draw PNG encoding failed");
        } finally { image[0].recycle(); }
    }

    private void sendHome() {
        android.app.UiAutomation automation = getUiAutomation();
        long downTime = SystemClock.uptimeMillis();
        require(automation.injectInputEvent(new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0), true),
                "Could not inject HOME key down");
        require(automation.injectInputEvent(new KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, 0), true),
                "Could not inject HOME key up");
    }

    private void await(Callable<Boolean> condition, String description) throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return;
            SystemClock.sleep(40);
        }
        throw new IllegalStateException("Timed out waiting for " + description);
    }

    private <T> T onMain(Callable<T> task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { return task.call(); }
            catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException("Main-thread diagnostics action failed", failure); }
        }
        final Object[] result = new Object[1];
        final Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try { result[0] = task.call(); }
            catch (Throwable problem) { failure[0] = problem; }
        });
        if (failure[0] instanceof RuntimeException) throw (RuntimeException) failure[0];
        if (failure[0] != null) throw new IllegalStateException("Main-thread diagnostics action failed", failure[0]);
        @SuppressWarnings("unchecked") T cast = (T) result[0];
        return cast;
    }

    private void onMain(Runnable task) { onMain(() -> { task.run(); return null; }); }

    private void cleanup() throws Exception {
        synchronized (createdActivities) {
            for (Activity activity : createdActivities) {
                if (activity.isFinishing() || activity.isDestroyed()) continue;
                onMain(activity::finish);
            }
        }
        if (currentActivity != null) await(() -> currentActivity == null, "diagnostics Activity finish");
        if (application != null && lifecycleCallbacks != null) application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks);
    }

    private void writeReport(Throwable failure) throws Exception {
        if (output == null) return;
        JSONObject report = new JSONObject();
        report.put("run_id", runId);
        report.put("suite", "diagnostics-screen-runtime");
        report.put("transport", "Android framework-local Activity; no service, remote endpoint, network, GPUI or JNI");
        report.put("result", failure == null ? "PASS" : "FAIL");
        report.put("failure", failure == null ? JSONObject.NULL : failure.toString());
        report.put("passed", new org.json.JSONArray(passes));
        report.put("skipped", new org.json.JSONArray(skips));
        try (FileOutputStream stream = new FileOutputStream(new File(output, "report.json"))) {
            stream.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof InvocationTargetException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private void pass(String description) { passes.add(description); }
}
