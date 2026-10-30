package dev.herdr.android;

import java.util.function.BooleanSupplier;

/**
 * Reopening the remembered session belongs to a launch, so only its first resume
 * may try, whatever that finds. When nothing is remembered yet, the pane opened
 * next becomes the remembered one, and Back from it must land on the host list.
 */
final class LaunchReopen {
    private boolean decided;

    /** Runs {@code reopen} on the first call only and returns its result; later calls return false. */
    boolean firstResume(BooleanSupplier reopen) {
        if (decided) return false;
        decided = true;
        return reopen.getAsBoolean();
    }
}
