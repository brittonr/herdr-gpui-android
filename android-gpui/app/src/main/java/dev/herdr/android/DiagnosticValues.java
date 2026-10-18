package dev.herdr.android;

/** Bounds for the only platform values admitted to a diagnostics result. */
final class DiagnosticValues {
    private DiagnosticValues() { }

    static String hardwareName(String value) {
        if (value == null) return "";
        StringBuilder clean = new StringBuilder(Math.min(value.length(), 128));
        int count = 0;
        for (int offset = 0; offset < value.length() && count < 128;) {
            int point = value.codePointAt(offset);
            offset += Character.charCount(point);
            int type = Character.getType(point);
            if (Character.isISOControl(point) || type == Character.FORMAT || type == Character.SURROGATE) continue;
            clean.appendCodePoint(point);
            count++;
        }
        return clean.toString();
    }

    static Integer batteryPercent(int value) {
        return value >= 0 && value <= 100 ? value : null;
    }

    static Long availableBytes(long value) {
        return value >= 0 ? value : null;
    }
}
