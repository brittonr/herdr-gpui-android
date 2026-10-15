package dev.herdr.android;

/** Small synchronous tap gate; a parent scroll cancellation can never become a macro click. */
final class GpuiShortcutTouch {
    private boolean pressed;

    boolean event(int action, boolean inside, boolean singlePointer) {
        switch (action) {
            case 0: // ACTION_DOWN
                pressed = inside && singlePointer;
                break;
            case 1: // ACTION_UP
                boolean clicked = pressed && inside && singlePointer;
                pressed = false;
                return clicked;
            case 2: // ACTION_MOVE
                pressed &= inside && singlePointer;
                break;
            case 3: // ACTION_CANCEL
            case 5: // ACTION_POINTER_DOWN
            case 6: // ACTION_POINTER_UP
                pressed = false;
                break;
            default: break;
        }
        return false;
    }

    boolean isPressed() { return pressed; }
}
