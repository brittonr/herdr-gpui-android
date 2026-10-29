package dev.herdr.android;

/**
 * Copy for the reconnect banner: transport wording is for logs, the banner says
 * what happened in words a person can act on. Kept out of the activity so the
 * mapping can be tested without a device.
 */
final class BannerCopy {
    private BannerCopy() { }

    static String human(String raw) {
        if (raw == null || raw.isEmpty()) return "Connection lost";
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("health check")) return "Connection lost · the host stopped answering";
        if (lower.contains("timed out")) return "Connection lost · the host timed out";
        if (lower.contains("closed") || lower.contains("ended") || lower.contains("disconnected")) {
            return "Connection lost";
        }
        // Anything unrecognised keeps its detail, bounded, rather than being lost.
        String detail = raw.length() > 120 ? raw.substring(0, 120) : raw;
        return "Connection lost · " + detail;
    }
}
