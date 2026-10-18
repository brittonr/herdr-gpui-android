package dev.gpui.mobile;

import android.view.inputmethod.EditorInfo;

/** Only explicit submit actions may turn an editor action into terminal input. */
final class ImeEditorAction {
    private ImeEditorAction() { }

    static boolean submits(int action) {
        switch (action) {
            case EditorInfo.IME_ACTION_UNSPECIFIED:
            case EditorInfo.IME_ACTION_NONE:
            case EditorInfo.IME_ACTION_GO:
            case EditorInfo.IME_ACTION_SEARCH:
            case EditorInfo.IME_ACTION_SEND:
                return true;
            default:
                // Navigation, Done, flags, and custom IDs must never execute terminal text.
                return false;
        }
    }
}
