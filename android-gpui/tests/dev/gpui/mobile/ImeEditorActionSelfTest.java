package dev.gpui.mobile;

import android.view.inputmethod.EditorInfo;

/** Host policy test; Android framework constants are inlined by javac. */
public final class ImeEditorActionSelfTest {
    public static void main(String[] args) {
        int checks = 0;
        for (int action = -1024; action <= 1024; action++) {
            boolean expected = action == EditorInfo.IME_ACTION_UNSPECIFIED
                    || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_GO
                    || action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_SEND;
            if (ImeEditorAction.submits(action) != expected)
                throw new AssertionError("Unexpected submit policy for action " + action);
            checks++;
        }
        for (int action : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE,
                EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_SEND}) {
            if (ImeEditorAction.submits(action)) throw new AssertionError("Unknown action submitted");
            checks++;
        }
        System.out.println("ImeEditorActionSelfTest passed (" + checks + " assertions)");
    }
}
