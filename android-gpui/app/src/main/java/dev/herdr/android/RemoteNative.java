package dev.herdr.android;

import android.content.Context;

/** Native remote connection boundary. No password is written to storage, logs or Intents. */
final class RemoteNative {
    private static boolean loaded;
    private RemoteNative() { }

    static synchronized void ensureLoaded(Context context) {
        if (loaded) return;
        System.loadLibrary("herdr_gpui_android");
        initialize(context.getApplicationContext());
        loaded = true;
    }

    static native void initialize(Context context);
    static native String inspect(String configJson);
    static native long connect(String configJson, String credentialJson);
    static native String poll(long handle, long sinceRevision, boolean mirror);
    static native String input(long handle, String paneId, String commandJson);
    static native String focus(long handle, String paneId);
    static native String focusTab(long handle, String tabId);
    static native String focusWorkspace(long handle, String workspaceId);
    static native String sessionAction(long handle, String action);
    static native String scrollTo(long handle, long offsetFromBottom);
    static native String setVisibleRows(long handle, long rows);
    static native String scrollLocal(long handle, long deltaRows, long generation,
            String title, String status, String shortcuts);
    static native void close(long handle);
}
