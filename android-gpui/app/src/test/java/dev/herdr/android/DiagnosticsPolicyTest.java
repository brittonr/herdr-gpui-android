package dev.herdr.android;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DiagnosticsPolicyTest {
    @Test public void localAccessRequiresEnablement() {
        assertFalse(DiagnosticsPolicy.canRead(false));
        assertTrue(DiagnosticsPolicy.canRead(true));
    }
}
