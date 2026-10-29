package dev.herdr.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class GpuiDisplayOptionsTest {
    @Test public void followingTheSystemPairsLatteWithMochaAndFixedThemesIgnoreIt() {
        assertEquals(GpuiDisplayOptions.PALETTE_LATTE,
                GpuiDisplayOptions.palette(GpuiDisplayOptions.THEME_SYSTEM, false));
        assertEquals(GpuiDisplayOptions.PALETTE_MOCHA,
                GpuiDisplayOptions.palette(GpuiDisplayOptions.THEME_SYSTEM, true));
        // A chosen palette stays chosen whatever the system theme does.
        for (int fixed = 0; fixed < GpuiDisplayOptions.THEME_SYSTEM; fixed++) {
            assertEquals(fixed, GpuiDisplayOptions.palette(fixed, false));
            assertEquals(fixed, GpuiDisplayOptions.palette(fixed, true));
        }
        // A stored choice this build does not know paints Default rather than failing.
        assertEquals(0, GpuiDisplayOptions.palette(-1, true));
        assertEquals(0, GpuiDisplayOptions.palette(GpuiDisplayOptions.THEME_SYSTEM + 1, false));
    }
}
