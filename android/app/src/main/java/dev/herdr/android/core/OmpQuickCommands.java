package dev.herdr.android.core;

import java.util.Arrays;
import java.util.List;

/** Editable Oh My Pi presets, verified against upstream v18.8.3. No RPC discovery. */
public final class OmpQuickCommands {
    public static final String REFERENCE_VERSION = "18.8.3";
    public static final String REFERENCE_COMMIT = "3e3c488a58d294e3a10051da588628e2cfb9d35c";

    private OmpQuickCommands() { }

    public static List<QuickCommand> defaults() {
        return QuickCommand.validatedCopy(Arrays.asList(
                new QuickCommand("Context", "/context", true),
                new QuickCommand("Usage", "/usage", true),
                new QuickCommand("Model", "/switch", true),
                new QuickCommand("Effort", "/effort", true),
                new QuickCommand("Tasks", "/todo", true),
                new QuickCommand("Jobs", "/jobs"),
                new QuickCommand("Session info", "/session info"),
                new QuickCommand("Compact context", "/compact"),
                new QuickCommand("Fresh state", "/fresh"),
                new QuickCommand("Retry turn", "/retry")));
    }

    /** Recompute from edited text, rather than keeping a stale preset description. */
    public static String warningFor(String text) {
        String value = text == null ? "" : text.trim();
        String verb = value.split("\\s+", 2)[0];
        if (verb.equals("/compact")) return "Summarizes and changes context; may call the model/provider.";
        if (verb.equals("/fresh")) return "Use while OMP is idle. Resets provider stream state; keeps the local transcript.";
        if (verb.equals("/retry")) return "Use after a failed turn while OMP is idle. Can resume model and tool execution.";
        return "";
    }
}
