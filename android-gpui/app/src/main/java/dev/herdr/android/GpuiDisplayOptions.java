package dev.herdr.android;

/** Local, non-secret display choices. No device names or vendor keycodes are inferred. */
final class GpuiDisplayOptions {
    static final int AUTO = 0, COMPACT = 1, STANDARD = 2;
    static final int ALT_AUTO = 0, ALT_SYMBOLS = 1, ALT_TERMINAL = 2;
    final int mode;
    final int fontSize;
    final int altMode;

    GpuiDisplayOptions(int mode, int fontSize, int altMode) {
        this.mode = mode >= AUTO && mode <= STANDARD ? mode : AUTO;
        this.fontSize = fontSize == 0 || fontSize >= 11 && fontSize <= 18 ? fontSize : 0;
        this.altMode = altMode >= ALT_AUTO && altMode <= ALT_TERMINAL ? altMode : ALT_AUTO;
    }

    boolean compact(float widthDp, float heightDp) {
        // Include narrow fold-cover windows while leaving wide, nearly-square inner screens standard.
        return mode == COMPACT || mode == AUTO && widthDp > 0 && heightDp > 0
                && (heightDp <= 500 || widthDp <= 480);
    }

    float effectiveFont(boolean compact, float systemScale) {
        if (!Float.isFinite(systemScale) || systemScale <= 0) systemScale = 1;
        float base = fontSize == 0 ? compact ? 12 : 13 : fontSize;
        return Math.max(11, Math.min(36, base * systemScale));
    }

    boolean altUsesSymbols() {
        // Geometry and IME visibility must never change a physical key’s meaning.
        return altMode != ALT_TERMINAL;
    }
}
