package dev.herdr.android;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class DiagnosticValuesTest {
    @Test public void batteryUnavailableNeverBecomesZeroOrAnInvalidPercentage() {
        assertNull(DiagnosticValues.batteryPercent(Integer.MIN_VALUE));
        assertNull(DiagnosticValues.batteryPercent(-1));
        assertNull(DiagnosticValues.batteryPercent(101));
        assertEquals(Integer.valueOf(0), DiagnosticValues.batteryPercent(0));
        assertEquals(Integer.valueOf(100), DiagnosticValues.batteryPercent(100));
    }

    @Test public void storagePreservesFullByteRangeButRejectsUnavailableValues() {
        assertNull(DiagnosticValues.availableBytes(-1));
        assertEquals(Long.valueOf(0), DiagnosticValues.availableBytes(0));
        assertEquals(Long.valueOf(Long.MAX_VALUE), DiagnosticValues.availableBytes(Long.MAX_VALUE));
    }

    @Test public void hardwareNamesCannotInjectControlsBidiOrUnpairedSurrogates() {
        assertEquals("Titan 2", DiagnosticValues.hardwareName("Ti\u0000t\u202ean\n 2\ud800"));
        assertEquals("", DiagnosticValues.hardwareName(null));
    }

    @Test public void truncationPreservesSupplementaryCharactersAndBoundsOutput() {
        StringBuilder input = new StringBuilder();
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 128; i++) { input.append("\ud83d\ude80"); expected.append("\ud83d\ude80"); }
        input.append("EXCESS");
        assertEquals(expected.toString(), DiagnosticValues.hardwareName(input.toString()));
    }
}
