package dev.herdr.android;

import android.os.Build;
import android.os.StatFs;
import android.os.BatteryManager;
import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Read-only, local device diagnostics. No shell, network listener, root request,
 * identifiers, or remote access. Called only by the diagnostics screen's worker.
 */
public final class DeviceDiagnostics {
    private DeviceDiagnostics() {}

    public static JSONObject snapshot(Context context) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("schema_version", 1);
        result.put("device_model", DiagnosticValues.hardwareName(Build.MODEL));
        result.put("manufacturer", DiagnosticValues.hardwareName(Build.MANUFACTURER));
        result.put("android_sdk", Build.VERSION.SDK_INT);
        Integer percent = null;
        try {
            BatteryManager battery = context.getSystemService(BatteryManager.class);
            if (battery != null) percent = DiagnosticValues.batteryPercent(
                    battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
        } catch (RuntimeException unavailable) { /* No platform diagnostics or paths enter the result. */ }
        result.put("battery_percent", percent == null ? JSONObject.NULL : percent);
        Long available = null;
        try {
            available = DiagnosticValues.availableBytes(new StatFs(
                    context.getFilesDir().getAbsolutePath()).getAvailableBytes());
        } catch (RuntimeException unavailable) { /* Storage can be unavailable without failing other fields. */ }
        result.put("app_storage_available_bytes", available == null ? JSONObject.NULL : available);
        return result;
    }
}
