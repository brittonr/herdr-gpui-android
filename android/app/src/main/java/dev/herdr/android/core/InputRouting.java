package dev.herdr.android.core;

/** Explicit focus/device gate: composing text must never also send remote terminal input. */
public final class InputRouting {
    private InputRouting() { }

    public static boolean shouldSendToTerminal(boolean enabled, boolean screen,
            boolean terminalFocused, boolean editorFocused, boolean physicalKeyboard,
            boolean softKeyboard, boolean dialogOpen) {
        return enabled && screen && terminalFocused && !editorFocused
                && physicalKeyboard && !softKeyboard && !dialogOpen;
    }
}
