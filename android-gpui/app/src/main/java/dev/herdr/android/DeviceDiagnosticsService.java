package dev.herdr.android;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Screen-scoped local reads, never an exported endpoint or a persistent monitor. */
public final class DeviceDiagnosticsService extends Service {
    public static final String ACTION_ENABLE = "dev.herdr.android.action.ENABLE_DIAGNOSTICS";
    public static final String ACTION_STOP = "dev.herdr.android.action.STOP_DIAGNOSTICS";
    public static final String CHANNEL_ID = "local-diagnostics";
    private static final int NOTIFICATION_ID = 1701;
    private static final long TIMEOUT_MS = 5000;
    // One process-wide slot bounds even platform calls that ignore interruption across service recreation.
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(0, 1, 30,
            TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "herdr-local-diagnostics");
                thread.setDaemon(true);
                return thread;
            });
    public enum Failure { DISABLED, BUSY, TIMEOUT, UNAVAILABLE }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LocalBinder binder = new LocalBinder();
    private boolean enabled;
    private boolean destroyed;
    private Runnable onDisabled;
    private ReadRequest pending;

    public final class LocalBinder extends Binder {
        public boolean isEnabled() {
            requireLocalMainThread();
            if (enabled && !notificationsAllowed()) disableSession();
            return enabled && !destroyed;
        }

        public void setOnDisabledListener(Runnable listener) {
            requireLocalMainThread();
            onDisabled = listener;
        }

        public void disable() {
            requireLocalMainThread();
            disableSession();
        }

        public void readSnapshot(Consumer<JSONObject> success, Consumer<Failure> failure) {
            requireLocalMainThread();
            if (success == null || failure == null) throw new IllegalArgumentException("Callbacks are required");
            if (!isEnabled()) { failure.accept(Failure.DISABLED); return; }
            if (pending != null) { failure.accept(Failure.BUSY); return; }
            ReadRequest request = new ReadRequest(success, failure);
            pending = request;
            Context application = getApplicationContext();
            request.task = new FutureTask<>(() -> {
                JSONObject snapshot = null;
                try {
                    snapshot = DeviceDiagnostics.snapshot(application);
                    if (snapshot.toString().getBytes(StandardCharsets.UTF_8).length > 4096) snapshot = null;
                } catch (Exception unavailable) { /* Only typed, bounded errors cross to the screen. */ }
                JSONObject result = snapshot;
                main.post(() -> complete(request, result, Failure.UNAVAILABLE));
                return null;
            });
            try {
                WORKER.execute(request.task);
                main.postDelayed(request.timeout, TIMEOUT_MS);
            } catch (RejectedExecutionException busy) {
                complete(request, null, Failure.BUSY);
            }
        }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_ENABLE.equals(intent.getAction()) || !notificationsAllowed()) {
            disableSession();
            return START_NOT_STICKY;
        }
        try {
            Notification notification = notification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else startForeground(NOTIFICATION_ID, notification);
            enabled = true;
        } catch (RuntimeException unavailable) {
            disableSession();
        }
        return START_NOT_STICKY;
    }

    @Override public boolean onUnbind(Intent intent) {
        disableSession();
        onDisabled = null;
        return false;
    }

    @Override public void onDestroy() {
        destroyed = true;
        disableSession();
        onDisabled = null;
        super.onDestroy();
    }

    private void requireLocalMainThread() {
        if (Binder.getCallingUid() != Process.myUid()) throw new SecurityException("App-local diagnostics only");
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Use the main thread");
    }

    private boolean notificationsAllowed() {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null || !manager.areNotificationsEnabled()) return false;
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Local diagnostics", NotificationManager.IMPORTANCE_LOW));
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
            return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private Notification notification() {
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, DeviceDiagnosticsService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(dev.herdr.android.R.drawable.ic_herdr)
                .setContentTitle("Local diagnostics enabled")
                .setContentText("Read-only, for the open diagnostics screen only")
                .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null, "Stop diagnostics", stop).build())
                .build();
    }

    private void disableSession() {
        enabled = false;
        if (pending != null) complete(pending, null, Failure.DISABLED);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        Runnable listener = onDisabled;
        onDisabled = null;
        if (listener != null) listener.run();
    }

    private void complete(ReadRequest request, JSONObject snapshot, Failure failure) {
        if (pending != request) return;
        pending = null;
        main.removeCallbacks(request.timeout);
        if (!enabled || destroyed) { snapshot = null; failure = Failure.DISABLED; }
        else if (SystemClock.elapsedRealtime() >= request.deadline) { snapshot = null; failure = Failure.TIMEOUT; }
        else if (!notificationsAllowed()) { snapshot = null; failure = Failure.DISABLED; }
        Consumer<JSONObject> success = request.success;
        Consumer<Failure> rejected = request.failure;
        request.success = null;
        request.failure = null;
        request.task.cancel(true);
        if (snapshot != null) success.accept(snapshot);
        else rejected.accept(failure);
        if (failure == Failure.DISABLED && enabled) disableSession();
    }

    private final class ReadRequest {
        final long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        final Runnable timeout = () -> complete(this, null, Failure.TIMEOUT);
        Consumer<JSONObject> success;
        Consumer<Failure> failure;
        FutureTask<Void> task;
        ReadRequest(Consumer<JSONObject> success, Consumer<Failure> failure) {
            this.success = success;
            this.failure = failure;
        }
    }
}
