package dev.herdr.android;

import android.annotation.TargetApi;
import android.app.Activity;
import android.os.Build;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/**
 * Takes Back only while a screen has somewhere to go back to. With no callback
 * registered, Android keeps its own predictive back animation to the previous
 * screen or home. From Android 13, an app that opts in (and every app targeting
 * Android 16) gets Back only through such callbacks, never {@code onBackPressed};
 * older versions still call {@code onBackPressed}, which each screen keeps.
 */
final class BackOwner {
    private final Activity activity;
    private final Runnable action;
    /** The registered {@code OnBackInvokedCallback}; typed loosely for Android 12 and older. */
    private Object callback;

    BackOwner(Activity activity, Runnable action) {
        this.activity = activity;
        this.action = action;
    }

    void setOwned(boolean owned) {
        if (Build.VERSION.SDK_INT < 33 || owned == (callback != null)) return;
        if (owned) callback = Api33.register(activity, action);
        else {
            Api33.unregister(activity, callback);
            callback = null;
        }
    }

    @TargetApi(33)
    private static final class Api33 {
        static Object register(Activity activity, Runnable action) {
            OnBackInvokedCallback callback = action::run;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }

        static void unregister(Activity activity, Object callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (OnBackInvokedCallback) callback);
        }
    }
}
