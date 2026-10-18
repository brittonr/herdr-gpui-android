package dev.herdr.android;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONException;

/** Explicit, screen-scoped client for the foreground local diagnostics service. */
public final class DeviceDiagnosticsActivity extends Activity {
    private static final int BG = Color.rgb(16, 18, 22);
    private static final int TEXT = Color.rgb(231, 234, 240);
    private static final int MUTED = Color.rgb(155, 167, 185);
    private static final int REQUEST_NOTIFICATIONS = 1;
    private static final String CHANNEL_ID = DeviceDiagnosticsService.CHANNEL_ID;

    private CheckBox enabled;
    private Button read;
    private TextView status;
    private TextView output;
    private DeviceDiagnosticsService.LocalBinder binder;
    private ServiceConnection serviceConnection;
    private boolean bound;
    private boolean visible;
    private boolean busy;
    private boolean changingSwitch;
    private boolean permissionRequestPending;
    private boolean permissionResultNeedsRetry;
    private int generation;

    @Override public void onCreate(Bundle state) {
        super.onCreate(null);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        createScreen();
        status.setText("Diagnostics are off. Nothing is collected until you enable them and choose Read diagnostics.");
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        if (permissionResultNeedsRetry) {
            permissionResultNeedsRetry = false;
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                status.setText("Notifications are allowed. Check the switch again to enable local diagnostics.");
            } else {
                status.setText("Notification permission is required for foreground diagnostics. Allow it in system settings, then check the switch again.");
            }
        } else if (!enabled.isChecked()) {
            status.setText("Diagnostics are off. Check the switch to explicitly enable them for this screen.");
        }
    }

    @Override protected void onPause() {
        visible = false;
        stopSession(null);
        super.onPause();
    }

    @Override protected void onDestroy() {
        visible = false;
        stopSession(null);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        // Diagnostic output and enablement are deliberately never persisted.
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS || !permissionRequestPending) return;
        permissionRequestPending = false;
        setEnabled(false);
        permissionResultNeedsRetry = true;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            status.setText("Notifications are allowed. Check the switch again to enable local diagnostics.");
        } else {
            status.setText("Notification permission is required for foreground diagnostics. Allow it in the system prompt, then check the switch again.");
        }
    }

    private void createScreen() {
        LinearLayout frame = column();
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        LinearLayout root = column();
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        frame.addView(root, new LinearLayout.LayoutParams(-1, -1));

        TextView title = label("Local device diagnostics", 24, TEXT);
        root.addView(title);
        root.addView(label("Read-only model, Android, battery and storage. Nothing is sent to a host.", 15, MUTED));
        status = label("", 14, MUTED);
        root.addView(status);

        enabled = new CheckBox(this);
        enabled.setText("Enable local diagnostics for this screen");
        enabled.setTextColor(TEXT);
        enabled.setMinHeight(dp(48));
        privateView(enabled);
        root.addView(enabled);

        read = new Button(this);
        read.setText("Read diagnostics");
        read.setAllCaps(false);
        read.setEnabled(false);
        read.setMinHeight(dp(48));
        privateView(read);
        root.addView(read);

        output = label("", 14, TEXT);
        output.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        privateView(scroll);
        scroll.setFillViewport(false);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(frame);

        enabled.setOnCheckedChangeListener((button, checked) -> {
            if (changingSwitch) return;
            if (checked) enableSession();
            else stopSession("Local diagnostics are off.");
        });
        read.setOnClickListener(view -> requestRead());
    }

    private void enableSession() {
        read.setEnabled(false);
        clearOutput();
        busy = false;
        if (!visible) {
            setEnabled(false);
            status.setText("Return to this screen and explicitly enable diagnostics.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            setEnabled(false);
            permissionRequestPending = true;
            status.setText("Notification permission is required for foreground diagnostics. Allow it in the system prompt, then check the switch again.");
            try {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            } catch (RuntimeException failure) {
                permissionRequestPending = false;
                status.setText("Unable to request notification permission. Enable notifications in system settings, then try again.");
            }
            return;
        }
        String notificationProblem;
        try {
            notificationProblem = notificationProblem();
        } catch (RuntimeException failure) {
            setEnabled(false);
            status.setText("Unable to check notification access. Enable notifications in system settings, then try again.");
            return;
        }
        if (notificationProblem != null) {
            setEnabled(false);
            status.setText(notificationProblem);
            return;
        }

        final int session = ++generation;
        Intent intent;
        try {
            intent = serviceIntent(DeviceDiagnosticsService.ACTION_ENABLE);
            startForegroundService(intent);
        } catch (RuntimeException failure) {
            failSession(session, "Unable to start local diagnostics. Check notification access and try again.");
            return;
        }

        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                DeviceDiagnosticsService.LocalBinder connected;
                try {
                    if (service == null) throw new ClassCastException();
                    connected = (DeviceDiagnosticsService.LocalBinder) service;
                } catch (RuntimeException failure) {
                    failSession(session, "Local diagnostics are unavailable. Check notification access and try again.");
                    return;
                }
                if (!isCurrent(session, this)) {
                    // Revoking an old callback must not disable a newer session on the same binder.
                    return;
                }
                binder = connected;
                boolean serviceEnabled;
                try {
                    connected.setOnDisabledListener(() -> {
                        if (isCurrent(session, this) && binder == connected) {
                            stopSession("Diagnostics were stopped. Check the switch to enable them again.");
                        }
                    });
                    if (!isCurrent(session, this)) return;
                    serviceEnabled = connected.isEnabled();
                } catch (RuntimeException failure) {
                    failSession(session, "Local diagnostics are unavailable. Check notification access and try again.");
                    return;
                }
                if (!serviceEnabled) {
                    failSession(session, "Local diagnostics could not be enabled. Check notification access and try again.");
                    return;
                }
                setEnabled(true);
                status.setText("Local diagnostics are enabled for this screen. Choose Read diagnostics to collect the allowlisted snapshot.");
                updateReadButton();
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                if (isCurrent(session, this)) failSession(session, "Local diagnostics are unavailable. Check the switch to try again.");
            }

            @Override public void onBindingDied(ComponentName name) {
                if (isCurrent(session, this)) failSession(session, "Local diagnostics are unavailable. Check the switch to try again.");
            }

            @Override public void onNullBinding(ComponentName name) {
                if (isCurrent(session, this)) failSession(session, "Local diagnostics are unavailable. Check the switch to try again.");
            }
        };
        try {
            if (!bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                failSession(session, "Unable to connect to local diagnostics. Check notification access and try again.");
                return;
            }
            serviceConnection = connection;
            bound = true;
            status.setText("Starting foreground local diagnostics…");
        } catch (RuntimeException failure) {
            failSession(session, "Unable to connect to local diagnostics. Check notification access and try again.");
        }
    }

    private String notificationProblem() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) {
            return "Notifications are disabled for this app. Enable them in system settings before opting in to diagnostics.";
        }
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        if (channel == null) {
            channel = new NotificationChannel(CHANNEL_ID, "Local diagnostics", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
            channel = manager.getNotificationChannel(CHANNEL_ID);
        }
        if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            return "The diagnostics notification channel is blocked. Allow it in system notification settings before opting in.";
        }
        return null;
    }

    private void requestRead() {
        final DeviceDiagnosticsService.LocalBinder current = binder;
        final ServiceConnection connection = serviceConnection;
        final int requestGeneration = generation;
        if (!visible || !enabled.isChecked() || current == null || !bound || busy) {
            updateReadButton();
            return;
        }
        if (!current.isEnabled()) {
            stopSession("Diagnostics were stopped. Check the switch to enable them again.");
            return;
        }
        busy = true;
        clearOutput();
        status.setText("Reading local diagnostics…");
        updateReadButton();
        try {
            current.readSnapshot(snapshot -> {
                if (!isCurrent(requestGeneration, connection) || binder != current || !enabled.isChecked()) return;
                if (!current.isEnabled()) {
                    stopSession("Diagnostics were stopped. Check the switch to enable them again.");
                    return;
                }
                busy = false;
                try {
                    output.setText(snapshot.toString(2));
                    status.setText("Snapshot read locally. No remote access or automatic collection.");
                } catch (JSONException | RuntimeException failure) {
                    clearOutput();
                    status.setText("Diagnostics returned an unavailable result. Try again.");
                }
                updateReadButton();
            }, failure -> {
                if (!isCurrent(requestGeneration, connection) || binder != current) return;
                busy = false;
                if (failure == DeviceDiagnosticsService.Failure.DISABLED) {
                    stopSession("Diagnostics were stopped. Check the switch to enable them again.");
                    return;
                }
                clearOutput();
                if (failure == DeviceDiagnosticsService.Failure.BUSY)
                    status.setText("A diagnostics request is already in progress. Try again shortly.");
                else if (failure == DeviceDiagnosticsService.Failure.TIMEOUT)
                    status.setText("The diagnostics request timed out. You can try again.");
                else status.setText("Local diagnostics are temporarily unavailable. You can try again.");
                updateReadButton();
            });
        } catch (RuntimeException failure) {
            if (!isCurrent(requestGeneration, connection)) return;
            busy = false;
            clearOutput();
            status.setText("Local diagnostics are temporarily unavailable. You can try again.");
            updateReadButton();
        }
    }

    private boolean isCurrent(int session, ServiceConnection connection) {
        return visible && generation == session && serviceConnection == connection && bound && enabled.isChecked();
    }

    private void failSession(int session, String message) {
        if (generation == session) stopSession(message);
    }

    private void stopSession(String message) {
        generation++;
        busy = false;
        setEnabled(false);
        clearOutput();
        DeviceDiagnosticsService.LocalBinder oldBinder = binder;
        binder = null;
        if (oldBinder != null) {
            oldBinder.setOnDisabledListener(null);
            oldBinder.disable();
        }
        ServiceConnection oldConnection = serviceConnection;
        serviceConnection = null;
        if (bound && oldConnection != null) {
            bound = false;
            try {
                unbindService(oldConnection);
            } catch (IllegalArgumentException ignored) {
                // The connection may already have been removed by Android.
            }
        } else {
            bound = false;
        }
        updateReadButton();
        stopStartedService();
        if (message != null && visible) status.setText(message);
    }

    private void stopStartedService() {
        try {
            stopService(serviceIntent(DeviceDiagnosticsService.ACTION_STOP));
        } catch (RuntimeException ignored) {
            // A failed enable can leave no service to stop; never leak an Activity error.
        }
    }

    private Intent serviceIntent(String action) {
        return new Intent(this, DeviceDiagnosticsService.class).setAction(action);
    }

    private void setEnabled(boolean checked) {
        if (enabled == null) return;
        changingSwitch = true;
        enabled.setChecked(checked);
        changingSwitch = false;
    }

    private void updateReadButton() {
        if (read != null) read.setEnabled(visible && enabled.isChecked() && binder != null && bound && !busy);
    }

    private void clearOutput() {
        if (output != null) output.setText("");
    }

    private LinearLayout column() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        privateView(result);
        return result;
    }

    private TextView label(String text, int size, int color) {
        TextView result = new TextView(this);
        result.setText(text);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setPadding(0, dp(6), 0, dp(6));
        privateView(result);
        return result;
    }

    private void privateView(View view) {
        view.setSaveEnabled(false);
        view.setSaveFromParentEnabled(false);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        if (Build.VERSION.SDK_INT >= 30) {
            view.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
