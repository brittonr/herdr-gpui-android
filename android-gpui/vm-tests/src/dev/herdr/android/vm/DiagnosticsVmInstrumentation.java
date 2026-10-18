package dev.herdr.android.vm;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Phase-one local diagnostics checks against the separately packaged target APK. */
public final class DiagnosticsVmInstrumentation extends Instrumentation {
    private static final String ACTIVITY = "dev.herdr.android.DeviceDiagnosticsActivity";
    private static final String SERVICE = "dev.herdr.android.DeviceDiagnosticsService";
    private static final String PERMISSION = "android.permission.POST_NOTIFICATIONS";
    private static final String ENABLE_LABEL = "Enable local diagnostics for this screen";
    private static final String READ_LABEL = "Read diagnostics";
    private static final String STOP_LABEL = "Stop diagnostics";
    private static final long WAIT_MS = 15000;
    private static final long READ_WAIT_MS = 10000;
    private static final int PROBE_MESSAGE = 1;

    private final List<String> passes = new ArrayList<>();
    private final List<String> skips = new ArrayList<>();
    private final JSONArray observations = new JSONArray();
    private final List<Activity> createdActivities = Collections.synchronizedList(new ArrayList<>());
    private final List<Binding> bindings = new ArrayList<>();
    private volatile Activity currentActivity;
    private volatile boolean activityResumed;
    private Application application;
    private Application.ActivityLifecycleCallbacks lifecycleCallbacks;
    private Context targetContext;
    private String targetPackage;
    private String runId;
    private File output;
    private Object retainedBinder;
    private boolean enableAttempted;

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
            verifyNonExportedService();
            checkBindingAloneCannotEnable();
            Activity screen = openScreen();
            assertDefaultOff(screen);
            checkDeniedNotificationPermission(screen);
            checkEnableReadAndDisable(screen);
            checkNotificationStop(screen);
            checkRequestBoundaries(screen);
            checkHomeAndRecreation(screen);
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
                    + "\nLocal foreground diagnostics only; no GPUI/JNI or network transport was exercised.\n");
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
                if (isDiagnosticsActivity(activity)) {
                    currentActivity = activity;
                    activityResumed = true;
                }
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

    private void verifyNonExportedService() throws Exception {
        ComponentName component = new ComponentName(targetPackage, SERVICE);
        ServiceInfo info = targetContext.getPackageManager().getServiceInfo(component, 0);
        require(!info.exported, "Installed diagnostics service is externally exported");
        pass("Installed Android component metadata marks the diagnostics service non-exported");
        checkExternalBindIsDenied();
    }

    /** A test-APK process with its own UID attempts the bind, unlike the instrumented target process. */
    private void checkExternalBindIsDenied() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch replied = new CountDownLatch(1);
        AtomicReference<Messenger> remote = new AtomicReference<>();
        AtomicReference<Boolean> denied = new AtomicReference<>();
        Handler replies = new Handler(Looper.getMainLooper(), message -> {
            if (message.what == PROBE_MESSAGE) {
                denied.set(message.arg1 == 1);
                replied.countDown();
                return true;
            }
            return false;
        });
        Messenger replyMessenger = new Messenger(replies);
        ServiceConnection probeConnection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                remote.set(new Messenger(binder));
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        Context instrumentationContext = getContext();
        require(instrumentationContext.getPackageName().equals(targetPackage + ".vmtests"),
                "Diagnostics probe is not running from the separate instrumentation package");
        Intent probeIntent = new Intent().setClassName(instrumentationContext.getPackageName(),
                ExternalBindProbeService.class.getName());
        boolean bound = instrumentationContext.bindService(probeIntent, probeConnection, Context.BIND_AUTO_CREATE);
        require(bound, "Cannot start the isolated external-bind probe");
        try {
            awaitLatch(connected, "isolated test-package probe connection");
            Message request = Message.obtain(null, PROBE_MESSAGE);
            request.replyTo = replyMessenger;
            remote.get().send(request);
            awaitLatch(replied, "external diagnostics-service bind denial");
            require(Boolean.TRUE.equals(denied.get()), "A distinct test-package UID could bind the diagnostics service");
            pass("A distinct instrumentation-package UID cannot bind the diagnostics service");
        } finally {
            instrumentationContext.unbindService(probeConnection);
        }
    }

    private void checkBindingAloneCannotEnable() throws Exception {
        Binding binding = bindTargetService();
        try {
            require(!binderIsEnabled(binding.binder), "Binding alone enabled local diagnostics");
            pass("Binding the local service without ACTION_ENABLE leaves diagnostics disabled");
        } finally {
            unbind(binding);
        }
    }

    private Activity openScreen() throws Exception {
        Intent intent = new Intent().setClassName(targetPackage, ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        onMain(() -> targetContext.startActivity(intent));
        await(() -> activityResumed && currentActivity != null, "diagnostics Activity launch");
        return currentActivity;
    }

    private void assertDefaultOff(Activity activity) throws Exception {
        onMain(() -> {
            CheckBox enable = enableBox(activity);
            Button read = readButton(activity);
            require(!enable.isChecked(), "Diagnostics was enabled on initial Activity launch");
            require(!read.isEnabled(), "Read diagnostics is enabled before explicit opt-in");
            require(!hasDiagnosticJson(activity), "Diagnostic output was restored or shown by default");
            checkControlBounds(activity);
        });
        require(!hasStopNotification(), "A diagnostics foreground notification exists before enable");
        pass("First screen launch shows opt-in unchecked, read disabled, and no restored diagnostic data");
        capture(activity, "01-default-off");
    }

    private void checkDeniedNotificationPermission(Activity activity) throws Exception {
        if (Build.VERSION.SDK_INT < 33) {
            skips.add("POST_NOTIFICATIONS denial case: runtime notification permission is not available before API 33");
            return;
        }
        require(targetContext.checkSelfPermission(PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED,
                "Could not establish notification denial for the selected target app");
        clickEnable(activity);
        stableFor(() -> {
            CheckBox box = enableBox(activity);
            return !box.isChecked() && !readButton(activity).isEnabled();
        }, () -> !hasStopNotification(), "notification permission denial remains fail-closed", 400);
        require(!binderEnabledIfAvailable(), "Notification denial enabled the service");
        require(!hasStopNotification(), "A foreground diagnostics notification appeared without notification permission");
        pass("On API 33+, explicit opt-in with POST_NOTIFICATIONS denied remains disabled and posts no foreground notice");
        getUiAutomation().grantRuntimePermission(targetPackage, PERMISSION);
        await(() -> targetContext.checkSelfPermission(PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                && activityResumed && activity.hasWindowFocus(), "return from the notification permission prompt");
        onMain(() -> {
            require(!enableBox(activity).isChecked() && !readButton(activity).isEnabled() && !hasStopNotification(),
                    "Granting notification permission enabled diagnostics without fresh user opt-in");
        });
        pass("Granting notifications alone leaves diagnostics disabled after the OS permission prompt closes");
    }

    private boolean binderEnabledIfAvailable() throws Exception {
        Binding binding = bindTargetService();
        try { return binderIsEnabled(binding.binder); }
        finally { unbind(binding); }
    }

    private void checkEnableReadAndDisable(Activity activity) throws Exception {
        NotificationManager manager = (NotificationManager) targetContext.getSystemService(Context.NOTIFICATION_SERVICE);
        require(manager != null && manager.areNotificationsEnabled(),
                "Enable diagnostics test requires notifications enabled for the selected app");
        enableAttempted = true;
        clickEnable(activity);
        await(() -> enableBox(activity).isChecked() && readButton(activity).isEnabled() && hasStopNotification(),
                "explicit enable, ongoing foreground notification, and Stop action");
        checkNotificationChannelUsable(manager);
        Binding binding = bindTargetService();
        retainedBinder = binding.binder;
        require(binderIsEnabled(retainedBinder), "Service did not report enabled after the explicit UI opt-in");
        onMain(() -> readButton(activity).performClick());
        await(() -> renderedJson(activity) != null, "explicit diagnostics read result in the Activity");
        JSONObject shown = onMain(() -> renderedJson(activity));
        validateDiagnosticSnapshot(shown);
        require(binderIsEnabled(retainedBinder), "Reading diagnostics unexpectedly disabled the service");
        ReadReply direct = requestSnapshot(retainedBinder);
        require(direct.calls.get() == 1 && direct.failure.get() == null && direct.value.get() instanceof JSONObject,
                "Binder read did not deliver exactly one successful snapshot");
        validateDiagnosticSnapshot((JSONObject) direct.value.get());
        require(direct.callbackOnMain.get(), "Successful binder callback did not run on the main thread");
        pass("Explicit Read diagnostics renders a bounded allowlisted snapshot of current Android values");
        pass("LocalBinder readSnapshot delivers one successful JSONObject callback on the main thread");
        capture(activity, "02-enabled-result");

        clickEnable(activity);
        await(() -> !enableBox(activity).isChecked() && !readButton(activity).isEnabled()
                        && !hasDiagnosticJson(activity) && !binderIsEnabled(retainedBinder),
                "unchecked diagnostics revocation and cleared output");
        assertDisabledRead(retainedBinder, "uncheck");
        require(!hasStopNotification(), "Unchecking diagnostics left the foreground notification active");
        pass("Unchecking opt-in disables the service, clears rendered diagnostics, and removes its notification");
        checkControlBounds(activity);
        capture(activity, "03-unchecked-off");
        unbind(binding);
    }

    private void checkNotificationStop(Activity activity) throws Exception {
        enableAttempted = true;
        clickEnable(activity);
        await(() -> enableBox(activity).isChecked() && readButton(activity).isEnabled() && hasStopNotification(),
                "re-enable before notification Stop action");
        Binding binding = bindTargetService();
        retainedBinder = binding.binder;
        onMain(() -> readButton(activity).performClick());
        await(() -> renderedJson(activity) != null, "diagnostic data before notification Stop");
        require(binderIsEnabled(retainedBinder), "Service did not enable before notification Stop");
        fireStopAction();
        await(() -> !enableBox(activity).isChecked() && !readButton(activity).isEnabled()
                        && !hasDiagnosticJson(activity) && !binderIsEnabled(retainedBinder)
                        && !hasStopNotification(),
                "notification Stop disables service and clears Activity output");
        assertDisabledRead(retainedBinder, "notification Stop");
        pass("The notification's explicit Stop action disables service, resets UI, and rejects retained-binder reads");
        capture(activity, "04-notification-stop-off");
        unbind(binding);
    }

    private void checkRequestBoundaries(Activity activity) throws Exception {
        clickEnable(activity);
        await(() -> readButton(activity).isEnabled() && hasStopNotification(), "enable before bounded request checks");
        Binding binding = bindTargetService();
        retainedBinder = binding.binder;
        Class<?> service = targetContext.getClassLoader().loadClass(SERVICE);
        java.lang.reflect.Field workerField = service.getDeclaredField("WORKER");
        workerField.setAccessible(true);
        java.util.concurrent.ThreadPoolExecutor worker = (java.util.concurrent.ThreadPoolExecutor) workerField.get(null);
        java.util.concurrent.ThreadFactory originalFactory = worker.getThreadFactory();
        long originalKeepAlive = worker.getKeepAliveTime(TimeUnit.NANOSECONDS);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            worker.setKeepAliveTime(1, TimeUnit.MILLISECONDS);
            await(() -> worker.getPoolSize() == 0, "idle collector thread retirement before scheduling fault");
            // Delay scheduling, not Android values or the production collector, to exercise the real deadline.
            worker.setThreadFactory(task -> originalFactory.newThread(() -> {
                started.countDown();
                try { require(release.await(WAIT_MS, TimeUnit.MILLISECONDS), "Scheduling fault was not released"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                task.run();
            }));
            ReadReply delayed = enqueueSnapshot(retainedBinder);
            awaitLatch(started, "accepted diagnostics request held before execution");
            ReadReply busy = requestSnapshot(retainedBinder);
            require("BUSY".equals(String.valueOf(busy.failure.get())) && busy.value.get() == null,
                    "A second request was queued instead of rejected while a read was in flight");
            awaitLatch(delayed.completed, "real five-second diagnostics deadline");
            require(delayed.calls.get() == 1 && delayed.value.get() == null
                    && "TIMEOUT".equals(String.valueOf(delayed.failure.get())) && delayed.callbackOnMain.get(),
                    "Expired diagnostics read did not complete once with TIMEOUT on the main thread");
            release.countDown();
            await(() -> worker.getActiveCount() == 0, "expired collector slot drained");
            onMain(() -> require(delayed.calls.get() == 1, "Expired read delivered a late result"));
            pass("One in-flight read rejects concurrent requests as BUSY; the real five-second deadline returns TIMEOUT without late data");
        } finally {
            release.countDown();
            worker.setThreadFactory(originalFactory);
            worker.setKeepAliveTime(originalKeepAlive, TimeUnit.NANOSECONDS);
        }
        ReadReply revoked = new ReadReply();
        onMain(() -> {
            invoke(retainedBinder, "readSnapshot", new Class<?>[]{java.util.function.Consumer.class,
                    java.util.function.Consumer.class}, revoked.success, revoked.onFailure);
            invoke(retainedBinder, "disable", new Class<?>[0]);
            return null;
        });
        awaitLatch(revoked.completed, "in-flight request revocation");
        await(() -> worker.getActiveCount() == 0, "revoked collector slot drained");
        onMain(() -> {
            require(revoked.calls.get() == 1 && revoked.value.get() == null
                    && "DISABLED".equals(String.valueOf(revoked.failure.get())) && revoked.callbackOnMain.get(),
                    "Revocation delivered stale diagnostics or completed more than once");
            require(!enableBox(activity).isChecked() && !readButton(activity).isEnabled() && !hasDiagnosticJson(activity),
                    "In-flight revocation did not reset the actual diagnostics screen");
        });
        pass("Revoking an accepted in-flight read delivers DISABLED exactly once and never renders stale data");
        unbind(binding);
    }

    private void checkHomeAndRecreation(Activity activity) throws Exception {
        enableAttempted = true;
        clickEnable(activity);
        await(() -> enableBox(activity).isChecked() && hasStopNotification(), "enable before HOME");
        onMain(() -> readButton(activity).performClick());
        await(() -> renderedJson(activity) != null, "diagnostic output before HOME");
        sendHome();
        await(() -> !activityResumed && currentActivity == activity, "real HOME pauses diagnostics Activity");
        await(() -> !enableBox(activity).isChecked() && !readButton(activity).isEnabled()
                        && !hasDiagnosticJson(activity) && !hasStopNotification(),
                "pause disables diagnostics and clears Activity output");
        require(!binderEnabledIfAvailable(), "Binding the paused service restarted diagnostics");
        pass("Real HOME/pause clears diagnostic output and stops the foreground diagnostics session");

        Intent intent = new Intent().setClassName(targetPackage, ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        onMain(() -> targetContext.startActivity(intent));
        await(() -> activityResumed && currentActivity != null, "returning to diagnostics screen after HOME");
        Activity returned = currentActivity;
        assertDefaultOffWithoutCapture(returned);
        capture(returned, "05-returned-off");

        onMain(() -> returned.recreate());
        await(() -> activityResumed && currentActivity != null && currentActivity != returned,
                "diagnostics Activity recreation");
        Activity recreated = currentActivity;
        assertDefaultOffWithoutCapture(recreated);
        checkControlBounds(recreated);
        pass("Activity recreation does not restore diagnostics enablement or diagnostic JSON");
        capture(recreated, "06-recreated-off");
    }

    private void assertDefaultOffWithoutCapture(Activity activity) throws Exception {
        onMain(() -> {
            require(!enableBox(activity).isChecked(), "Diagnostics enablement survived screen return/recreation");
            require(!readButton(activity).isEnabled(), "Read remained enabled after screen return/recreation");
            require(!hasDiagnosticJson(activity), "Diagnostic JSON survived screen return/recreation");
        });
        require(!hasStopNotification(), "Foreground diagnostics notification survived screen return/recreation");
        require(!binderEnabledIfAvailable(), "Service remained enabled after screen return/recreation");
    }

    private void assertDisabledRead(Object binder, String transition) throws Exception {
        ReadReply reply = requestSnapshot(binder);
        require(reply.calls.get() == 1 && reply.value.get() == null && reply.failure.get() != null
                        && "DISABLED".equals(reply.failure.get().toString()),
                "Retained binder read did not fail with DISABLED after " + transition);
        require(reply.callbackOnMain.get(), "Disabled binder callback did not run on the main thread");
    }

    private Binding bindTargetService() throws Exception {
        Binding binding = new Binding();
        Intent intent = new Intent().setClassName(targetPackage, SERVICE);
        binding.bound = onMain(() -> targetContext.bindService(intent, binding.connection, Context.BIND_AUTO_CREATE));
        require(binding.bound, "Could not bind to the local diagnostics service");
        awaitLatch(binding.connected, "local diagnostics binder connection");
        bindings.add(binding);
        return binding;
    }

    private void unbind(Binding binding) {
        if (binding == null || !binding.bound) return;
        binding.bound = false;
        try { onMain(() -> targetContext.unbindService(binding.connection)); }
        catch (Throwable failure) { throw new IllegalStateException("Could not unbind diagnostics test connection", failure); }
    }

    private boolean binderIsEnabled(Object binder) throws Exception {
        if (binder == null) return false;
        return onMain(() -> (Boolean) invoke(binder, "isEnabled", new Class<?>[0]));
    }

    private ReadReply requestSnapshot(Object binder) throws Exception {
        ReadReply reply = enqueueSnapshot(binder);
        if (!reply.completed.await(READ_WAIT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Timed out waiting for LocalBinder readSnapshot callback");
        }
        require(reply.calls.get() == 1, "LocalBinder readSnapshot callback count was not exactly one");
        return reply;
    }

    private ReadReply enqueueSnapshot(Object binder) throws Exception {
        ReadReply reply = new ReadReply();
        onMain(() -> invoke(binder, "readSnapshot", new Class<?>[]{java.util.function.Consumer.class,
                        java.util.function.Consumer.class}, reply.success, reply.onFailure));
        return reply;
    }

    private void validateDiagnosticSnapshot(JSONObject data) throws Exception {
        require(data != null, "Diagnostics read returned no JSONObject");
        Set<String> expected = new HashSet<>();
        Collections.addAll(expected, "schema_version", "device_model", "manufacturer", "android_sdk",
                "battery_percent", "app_storage_available_bytes");
        Set<String> actual = new HashSet<>();
        for (Iterator<String> keys = data.keys(); keys.hasNext();) actual.add(keys.next());
        require(actual.equals(expected), "Diagnostics JSON contains missing or non-allowlisted fields");
        require(data.optInt("schema_version", -1) == 1, "Diagnostics schema version is not 1");
        require(data.optInt("android_sdk", -1) == Build.VERSION.SDK_INT, "Diagnostics SDK value is not the running Android API");
        String model = data.optString("device_model", "");
        String manufacturer = data.optString("manufacturer", "");
        require(model.equals(Build.MODEL) && manufacturer.equals(Build.MANUFACTURER),
                "Diagnostics model/manufacturer values do not match Android Build values");
        require(isBoundedSanitized(model) && isBoundedSanitized(manufacturer),
                "Diagnostics model/manufacturer exceeded the sanitized field boundary");
        Object batteryValue = data.opt("battery_percent");
        require(batteryValue == JSONObject.NULL || (batteryValue instanceof Number
                        && ((Number) batteryValue).intValue() >= 0 && ((Number) batteryValue).intValue() <= 100),
                "Diagnostics battery percentage is neither null nor in [0,100]");
        int actualBattery = batteryPercent();
        if (actualBattery < 0 || actualBattery > 100) {
            require(batteryValue == JSONObject.NULL, "Unavailable Android battery capacity was reported as a value");
        } else {
            require(batteryValue instanceof Number && ((Number) batteryValue).intValue() == actualBattery,
                    "Diagnostics battery percentage does not match Android BatteryManager");
        }
        Object storageValue = data.opt("app_storage_available_bytes");
        require(storageValue instanceof Number && ((Number) storageValue).longValue() >= 0,
                "Diagnostics app-storage capacity is not a nonnegative byte count");
        long now = new android.os.StatFs(targetContext.getFilesDir().getAbsolutePath()).getAvailableBytes();
        long reported = ((Number) storageValue).longValue();
        require(distance(reported, now) <= 16L * 1024L * 1024L,
                "Diagnostics app-storage capacity does not match current Android filesystem values");
        require(data.toString().getBytes(StandardCharsets.UTF_8).length <= 4096,
                "Diagnostics JSON exceeds the 4 KiB UTF-8 result bound");
        observations.put(new JSONObject().put("schema_version", 1).put("field_count", actual.size())
                .put("utf8_bytes", data.toString().getBytes(StandardCharsets.UTF_8).length)
                .put("model_code_points", model.codePointCount(0, model.length()))
                .put("manufacturer_code_points", manufacturer.codePointCount(0, manufacturer.length()))
                .put("battery_available", batteryValue != JSONObject.NULL));
    }

    private static boolean isBoundedSanitized(String value) {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > 128) return false;
        for (int offset = 0; offset < value.length();) {
            int point = value.codePointAt(offset);
            if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT) return false;
            offset += Character.charCount(point);
        }
        return true;
    }

    private int batteryPercent() {
        BatteryManager battery = (BatteryManager) targetContext.getSystemService(Context.BATTERY_SERVICE);
        return battery == null ? -1 : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    private static long distance(long left, long right) {
        if (left >= right) return left - right;
        return right - left;
    }

    private void checkNotificationChannelUsable(NotificationManager manager) {
        require(manager.areNotificationsEnabled(), "Notifications became disabled during local diagnostics enable");
        List<NotificationChannel> channels = manager.getNotificationChannels();
        for (NotificationChannel channel : channels) {
            if (channel.getId().equals(findStopNotification().getChannelId())) {
                require(channel.getImportance() != NotificationManager.IMPORTANCE_NONE,
                        "Diagnostics notification channel is blocked");
                return;
            }
        }
        throw new IllegalStateException("Foreground diagnostics notification channel is missing");
    }

    private boolean hasStopNotification() {
        return findStopNotification() != null;
    }

    private Notification findStopNotification() {
        NotificationManager manager = (NotificationManager) targetContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return null;
        for (android.service.notification.StatusBarNotification status : manager.getActiveNotifications()) {
            Notification notification = status.getNotification();
            if ((notification.flags & Notification.FLAG_ONGOING_EVENT) == 0 || notification.actions == null) continue;
            for (Notification.Action action : notification.actions) {
                if (action.title != null && STOP_LABEL.contentEquals(action.title) && action.actionIntent != null) {
                    return notification;
                }
            }
        }
        return null;
    }

    private void fireStopAction() throws Exception {
        Notification notification = findStopNotification();
        require(notification != null, "Could not find the active ongoing diagnostics notification");
        Notification.Action stop = null;
        for (Notification.Action action : notification.actions) {
            if (action.title != null && STOP_LABEL.contentEquals(action.title)) { stop = action; break; }
        }
        require(stop != null && stop.actionIntent != null, "Foreground notification has no explicit Stop diagnostics action");
        stop.actionIntent.send();
    }

    private void clickEnable(Activity activity) {
        onMain(() -> {
            CheckBox box = enableBox(activity);
            box.performClick();
        });
    }

    private CheckBox enableBox(Activity activity) {
        CheckBox found = findText(activity.getWindow().getDecorView(), CheckBox.class, ENABLE_LABEL);
        if (found == null) throw new IllegalStateException("Diagnostics opt-in checkbox is missing");
        return found;
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

    private boolean hasDiagnosticJson(Activity activity) {
        return renderedJson(activity) != null;
    }

    private static void collectTextViews(View root, List<TextView> result) {
        if (root instanceof TextView) result.add((TextView) root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) collectTextViews(group.getChildAt(index), result);
        }
    }

    private void checkControlBounds(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        int windowWidth = decor.getWidth();
        int contentWidth = activity.findViewById(android.R.id.content).getWidth();
        require(windowWidth > 0 && contentWidth > 0 && contentWidth <= windowWidth,
                "Diagnostics screen has invalid actual window/content bounds");
        for (View control : new View[]{enableBox(activity), readButton(activity)}) {
            Rect visible = new Rect();
            require(control.getGlobalVisibleRect(visible), "Diagnostics control is not visible in the current viewport");
            require(visible.left >= 0 && visible.right <= windowWidth && visible.width() == control.getWidth(),
                    "Diagnostics control is clipped by the current viewport width");
        }
        try {
            observations.put(new JSONObject().put("viewport_width_px", windowWidth)
                    .put("content_width_px", contentWidth)
                    .put("content_width_dp", Math.round(contentWidth / targetContext.getResources().getDisplayMetrics().density)));
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Cannot record viewport bounds", failure);
        }
    }

    private void capture(Activity activity, String name) throws Exception {
        final Bitmap[] image = new Bitmap[1];
        onMain(() -> {
            require((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0,
                    "Diagnostics Activity FLAG_SECURE was unexpectedly absent");
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
        require(automation.injectInputEvent(new KeyEvent(downTime, downTime,
                        KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0), true),
                "Could not inject HOME key down");
        require(automation.injectInputEvent(new KeyEvent(downTime, SystemClock.uptimeMillis(),
                        KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, 0), true),
                "Could not inject HOME key up");
    }

    private void stableFor(Callable<Boolean> visibleCondition, Callable<Boolean> systemCondition,
                           String description, long stableMs) throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_MS;
        long stableSince = -1;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean stable = onMain(visibleCondition) && systemCondition.call();
            if (stable) {
                if (stableSince < 0) stableSince = SystemClock.uptimeMillis();
                if (SystemClock.uptimeMillis() - stableSince >= stableMs) return;
            } else stableSince = -1;
            SystemClock.sleep(40);
        }
        throw new IllegalStateException("Timed out waiting for " + description);
    }

    private void await(Callable<Boolean> condition, String description) throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return;
            SystemClock.sleep(40);
        }
        throw new IllegalStateException("Timed out waiting for " + description);
    }

    private static void awaitLatch(CountDownLatch latch, String description) throws InterruptedException {
        if (!latch.await(WAIT_MS, TimeUnit.MILLISECONDS)) throw new IllegalStateException("Timed out waiting for " + description);
    }

    private <T> T onMain(Callable<T> task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { return task.call(); }
            catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException("Main-thread diagnostics action failed", failure); }
        }
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        new Handler(Looper.getMainLooper()).post(() -> {
            try { value.set(task.call()); }
            catch (Throwable failure) { failed.set(failure); }
            finally { completed.countDown(); }
        });
        try {
            if (!completed.await(WAIT_MS, TimeUnit.MILLISECONDS)) throw new IllegalStateException("Main-thread diagnostics barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted at main-thread diagnostics barrier", interrupted);
        }
        if (failed.get() != null) throw new IllegalStateException("Main-thread diagnostics action failed", failed.get());
        return value.get();
    }

    private void onMain(Runnable task) {
        onMain(() -> { task.run(); return null; });
    }

    private Object invoke(Object receiver, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = receiver.getClass().getMethod(name, types);
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException error) {
            Throwable cause = unwrap(error);
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        }
    }

    private void cleanup() throws Exception {
        if (application != null && lifecycleCallbacks != null) application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks);
        if (retainedBinder != null) {
            try { onMain(() -> invoke(retainedBinder, "disable", new Class<?>[0])); }
            catch (Throwable ignored) { }
        }
        if (enableAttempted && targetContext != null) {
            Intent stop = new Intent().setClassName(targetPackage, SERVICE).setAction("dev.herdr.android.action.STOP_DIAGNOSTICS");
            try { onMain(() -> targetContext.stopService(stop)); }
            catch (Throwable ignored) { }
        }
        synchronized (createdActivities) {
            for (Activity activity : createdActivities) {
                try { onMain(() -> { if (!activity.isFinishing() && !activity.isDestroyed()) activity.finish(); }); }
                catch (Throwable ignored) { }
            }
        }
        for (int index = bindings.size() - 1; index >= 0; index--) unbind(bindings.get(index));
        bindings.clear();
    }

    private void writeReport(Throwable failure) throws Exception {
        if (output == null) return;
        JSONObject report = new JSONObject();
        report.put("run_id", runId);
        report.put("target_package", targetPackage);
        report.put("suite", "diagnostics-phase-1-runtime");
        report.put("android_api", Build.VERSION.SDK_INT);
        report.put("observations", observations);
        report.put("result", failure == null ? "PASS" : "FAIL");
        report.put("failure", failure == null ? JSONObject.NULL : failure.toString());
        report.put("passed", new JSONArray(passes));
        report.put("skipped", new JSONArray(skips));
        report.put("transport", "Android framework-local Service binder; no remote endpoint, network, GPUI or JNI");
        report.put("screen_capture", "In-process View.draw from FLAG_SECURE diagnostics Activity");
        try (FileOutputStream stream = new FileOutputStream(new File(output, "report.json"))) {
            stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
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

    private final class Binding {
        final CountDownLatch connected = new CountDownLatch(1);
        final ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                Binding.this.binder = binder;
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        volatile boolean bound;
        volatile Object binder;
    }

    private final class ReadReply {
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Object> value = new AtomicReference<>();
        final AtomicReference<Object> failure = new AtomicReference<>();
        final AtomicBoolean callbackOnMain = new AtomicBoolean(true);
        final java.util.function.Consumer<JSONObject> success = result -> {
            calls.incrementAndGet();
            callbackOnMain.compareAndSet(true, Looper.myLooper() == Looper.getMainLooper());
            value.set(result);
            completed.countDown();
        };
        final java.util.function.Consumer<Object> onFailure = error -> {
            calls.incrementAndGet();
            callbackOnMain.compareAndSet(true, Looper.myLooper() == Looper.getMainLooper());
            this.failure.set(error);
            completed.countDown();
        };
    }

    /** Isolated test-APK UID used to prove the target service cannot be bound externally. */
    public static final class ExternalBindProbeService extends Service {
        private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (message.what != PROBE_MESSAGE || message.replyTo == null) return false;
            boolean denied = false;
            boolean bound = false;
            ServiceConnection targetConnection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) { }
                @Override public void onServiceDisconnected(ComponentName name) { }
            };
            String testPackage = getPackageName();
            String suffix = ".vmtests";
            if (testPackage.endsWith(suffix)) {
                String targetPackage = testPackage.substring(0, testPackage.length() - suffix.length());
                Intent target = new Intent().setClassName(targetPackage, SERVICE);
                try {
                    bound = bindService(target, targetConnection, Context.BIND_AUTO_CREATE);
                    denied = !bound;
                } catch (SecurityException expected) { denied = true; }
                finally {
                    // Android can register a dispatcher before rejecting a non-exported bind.
                    try { unbindService(targetConnection); }
                    catch (IllegalArgumentException notRegistered) { }
                }
            }
            Message response = Message.obtain(null, PROBE_MESSAGE);
            response.arg1 = denied ? 1 : 0;
            try { message.replyTo.send(response); }
            catch (RemoteException ignored) { }
            return true;
        }));

        @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }
    }
}
