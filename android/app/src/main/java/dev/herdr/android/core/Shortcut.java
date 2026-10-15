package dev.herdr.android.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One explicit terminal action. Text actions never append an Enter key. */
public final class Shortcut {
    public static final int MAX_BUTTONS = 24;
    public static final int MAX_TEXT_BYTES = 65536;
    public static final int MAX_CONFIGURATION_CHARS = 262144;
    public static final String DEFAULT_CONFIGURATION = "Esc = key:esc\n"
            + "Tab = key:tab\nEnter = key:enter\nCtrl+C = key:ctrl+c\n"
            + "Up = key:up\nDown = key:down\nLeft = key:left\nRight = key:right";

    private final String label;
    private final List<String> keys;
    private final String text;

    private Shortcut(String label, List<String> keys, String text) {
        if (label == null || label.trim().isEmpty() || label.length() > 40
                || HostProfile.hasControl(label) || label.indexOf('=') >= 0) {
            throw new IllegalArgumentException("Shortcut label must contain 1–40 characters without '=' or line breaks");
        }
        JsonStrings.utf8(label);
        this.label = label.trim();
        this.keys = keys;
        this.text = text;
    }

    public static Shortcut keys(String label, String sequence) {
        return new Shortcut(label, KeyChord.parseSequence(sequence), null);
    }

    public static Shortcut text(String label, String text) {
        validateText(text);
        return new Shortcut(label, null, text);
    }

    public String getLabel() { return label; }
    public boolean isText() { return text != null; }
    public String getText() { return text; }
    public List<String> getKeys() { return keys == null ? Collections.<String>emptyList() : keys; }
    public String getRouteSuffix() { return isText() ? "text" : "keys"; }

    public String toJsonBody() {
        if (isText()) return "{\"text\":" + JsonStrings.quote(text) + "}";
        StringBuilder out = new StringBuilder("{\"keys\":[");
        for (String key : keys) {
            if (out.length() > 9) out.append(',');
            out.append(JsonStrings.quote(key));
        }
        return out.append("]}").toString();
    }

    public static void validateText(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_TEXT_BYTES
                || JsonStrings.utf8(value).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Text must contain 1–65536 UTF-8 bytes");
        }
    }

    /** Lines: Label = key:ctrl+c | Label = keys:esc,up,enter | Label = text:literal. */
    public static List<Shortcut> parseConfiguration(String configuration) {
        if (configuration == null || configuration.length() > MAX_CONFIGURATION_CHARS) {
            throw new IllegalArgumentException("Shortcut configuration is too large");
        }
        List<Shortcut> result = new ArrayList<Shortcut>();
        Set<String> labels = new HashSet<String>();
        String[] lines = configuration.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) continue;
            try {
                int equals = line.indexOf('=');
                if (equals < 1 || result.size() >= MAX_BUTTONS) {
                    throw new IllegalArgumentException("Use at most 24 lines in the format Label = key:ctrl+c");
                }
                String label = line.substring(0, equals).trim();
                String action = line.substring(equals + 1);
                int start = 0;
                while (start < action.length() && action.charAt(start) == ' ') start++;
                action = action.substring(start);
                Shortcut shortcut;
                if (action.startsWith("text:")) {
                    shortcut = text(label, unescape(action.substring(5)));
                } else if (action.startsWith("keys:")) {
                    shortcut = keys(label, action.substring(5));
                } else if (action.startsWith("key:")) {
                    shortcut = keys(label, action.substring(4));
                    if (shortcut.keys.size() != 1) throw new IllegalArgumentException("Use keys: for a sequence");
                } else {
                    throw new IllegalArgumentException("Action must start with key:, keys: or text:");
                }
                if (!labels.add(shortcut.label)) throw new IllegalArgumentException("Shortcut labels must be unique");
                result.add(shortcut);
            } catch (IllegalArgumentException e) {
                // Do not include the user's macro or an untrusted token in the diagnostic.
                throw new IllegalArgumentException("Shortcut line " + (i + 1) + ": " + e.getMessage());
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static String formatConfiguration(List<Shortcut> shortcuts) {
        if (shortcuts == null || shortcuts.size() > MAX_BUTTONS) {
            throw new IllegalArgumentException("Use at most 24 shortcuts");
        }
        StringBuilder out = new StringBuilder();
        for (Shortcut shortcut : shortcuts) {
            if (shortcut == null) throw new IllegalArgumentException("Shortcut is required");
            if (out.length() > 0) out.append('\n');
            out.append(shortcut.label).append(" = ");
            if (shortcut.isText()) {
                out.append("text:").append(escape(shortcut.text));
            } else {
                out.append(shortcut.keys.size() == 1 ? "key:" : "keys:");
                for (int i = 0; i < shortcut.keys.size(); i++) {
                    if (i > 0) out.append(',');
                    out.append(shortcut.keys.get(i));
                }
            }
            if (out.length() > MAX_CONFIGURATION_CHARS) {
                throw new IllegalArgumentException("Shortcut configuration is too large");
            }
        }
        String result = out.toString();
        parseConfiguration(result);
        return result;
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                if (++i == value.length()) throw new IllegalArgumentException("Incomplete text escape");
                switch (value.charAt(i)) {
                    case '\\': out.append('\\'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    default: throw new IllegalArgumentException("Text supports only \\\\, \\n, \\r and \\t escapes");
                }
            } else {
                if (c == '\r' || c == '\n') throw new IllegalArgumentException("Escape line breaks in text macros");
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    @Override public String toString() { return "Shortcut{" + label + ", " + getRouteSuffix() + "}"; }
}
