package dev.herdr.android;

import android.os.Build;
import android.os.StatFs;
import android.os.BatteryManager;
import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Read-only, local device diagnostics. No shell, network listener, root request,
 * background service, identifiers, or remote access.
 */
public final class DeviceDiagnostics {
    private DeviceDiagnostics() {}

    public static JSONObject snapshot(Context context) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("schema_version", 1);
        result.put("device_model", Build.MODEL);
        result.put("manufacturer", Build.MANUFACTURER);
        result.put("android_sdk", Build.VERSION.SDK_INT);
        BatteryManager battery = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        int percent = battery == null ? -1
                : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        result.put("battery_percent", percent >= 0 && percent <= 100 ? percent : JSONObject.NULL);
        StatFs storage = new StatFs(context.getFilesDir().getAbsolutePath());
        result.put("app_storage_available_bytes", storage.getAvailableBytes());
        return result;
    }
}
