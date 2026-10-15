package dev.herdr.android.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A saved single-line text insertion. Submission is always a separate terminal action. */
public final class QuickCommand {
    public static final int MAX_COMMANDS = 24;
    public static final int MAX_LABEL_CHARS = 40;
    public static final int MAX_TEXT_BYTES = 4096;

    private final String label;
    private final String text;
    private final boolean pinned;

    public QuickCommand(String label, String text) {
        this(label, text, false);
    }

    public QuickCommand(String label, String text, boolean pinned) {
        if (label == null || label.trim().isEmpty() || label.length() > MAX_LABEL_CHARS) {
            throw new IllegalArgumentException("Command label must contain 1–40 characters");
        }
        requireSingleLine(label);
        JsonStrings.utf8(label);
        validateText(text);
        this.label = label.trim();
        this.text = text;
        this.pinned = pinned;
    }

    public String getLabel() { return label; }
    public String getText() { return text; }
    public boolean isPinned() { return pinned; }

    public static void validateText(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_TEXT_BYTES
                || JsonStrings.utf8(text).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Command text must contain 1–4096 UTF-8 bytes");
        }
        requireSingleLine(text);
    }

    public static List<QuickCommand> validatedCopy(List<QuickCommand> commands) {
        if (commands == null || commands.size() > MAX_COMMANDS) {
            throw new IllegalArgumentException("Save at most 24 quick commands per host");
        }
        List<QuickCommand> result = new ArrayList<QuickCommand>(commands.size());
        Set<String> labels = new HashSet<String>();
        for (QuickCommand command : commands) {
            if (command == null) throw new IllegalArgumentException("Quick command is required");
            if (!labels.add(command.label)) throw new IllegalArgumentException("Quick command labels must be unique");
            result.add(command);
        }
        return Collections.unmodifiableList(result);
    }

    private static void requireSingleLine(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') {
                throw new IllegalArgumentException("Quick commands must be one line without control characters");
            }
        }
    }

    @Override public String toString() { return "QuickCommand{" + label + ", text redacted}"; }
}
