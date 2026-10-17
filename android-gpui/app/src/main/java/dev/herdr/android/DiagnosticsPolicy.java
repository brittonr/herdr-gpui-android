package dev.herdr.android;

/** Local diagnostics permission gate. */
public final class DiagnosticsPolicy {
    private DiagnosticsPolicy() {}
    public static boolean canRead(boolean enabled) {
        return enabled;
    }
}
