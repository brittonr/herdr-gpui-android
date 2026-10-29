package dev.herdr.android;

/** Pure display policy checks. These do not stand in for measured Android frame tests. */
public final class GpuiDisplayOptionsSelfTest {
    private static int checks;

    public static void main(String[] args) {
        GpuiDisplayOptions auto = new GpuiDisplayOptions(0, 0, 0);
        check(auto.compact(432, 480), "1080x1200 at density 400 chooses compact");
        check(auto.compact(443, 993), "Pixel 9 Pro Fold cover viewport chooses compact");
        check(!auto.compact(800, 840), "large nearly-square inner viewport stays standard");
        check(auto.compact(480, 900), "fold-cover width threshold is inclusive");
        check(auto.compact(480, 432), "short wide layout chooses compact");
        check(auto.compact(360, 900), "narrow portrait chooses compact");
        check(!auto.compact(481, 501), "above both compact thresholds stays standard");
        check(auto.compact(500, 500), "height threshold is inclusive");
        check(!auto.compact(0, 480), "unmeasured width does not select compact");
        check(!auto.compact(432, 0), "unmeasured height does not select compact");
        check(!auto.compact(-1, 480), "negative geometry is not compact");
        check(!auto.compact(Float.NaN, 480), "NaN geometry is not compact");
        check(new GpuiDisplayOptions(1, 0, 0).compact(900, 1200), "explicit compact overrides large geometry");
        check(!new GpuiDisplayOptions(2, 0, 0).compact(320, 240), "explicit standard overrides small geometry");
        equal(12, auto.effectiveFont(true, 1), "compact auto font");
        equal(13, auto.effectiveFont(false, 1), "standard auto font");
        equal(24, auto.effectiveFont(true, 2), "system accessibility font scale applies");
        equal(11, auto.effectiveFont(true, 0.5f), "effective font has readable minimum");
        equal(36, auto.effectiveFont(false, 10), "effective font is bounded");
        for (float invalid : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            equal(12, auto.effectiveFont(true, invalid), "invalid system font scale uses 1");
        }
        for (int font = 11; font <= 18; font++) {
            GpuiDisplayOptions explicit = new GpuiDisplayOptions(0, font, 0);
            equal(font, explicit.effectiveFont(true, 1), "every supported font is preserved");
            equal(Math.min(36, font * 2), explicit.effectiveFont(false, 2), "explicit font respects accessibility scale");
        }
        for (int invalid : new int[]{-1, 1, 10, 19, Integer.MAX_VALUE}) {
            GpuiDisplayOptions options = new GpuiDisplayOptions(invalid, invalid, invalid);
            check(options.fontSize == 0, "invalid font resets to Auto");
        }
        for (int invalid : new int[]{-1, 3, Integer.MAX_VALUE}) {
            GpuiDisplayOptions options = new GpuiDisplayOptions(invalid, 0, invalid);
            check(options.mode == GpuiDisplayOptions.AUTO && options.altMode == GpuiDisplayOptions.ALT_AUTO,
                    "invalid profile and Alt values reset to Auto");
        }
        check(auto.altUsesSymbols(), "Auto Alt uses device symbols");
        check(new GpuiDisplayOptions(1, 0, 0).altUsesSymbols()
                && new GpuiDisplayOptions(2, 0, 0).altUsesSymbols(), "layout never changes Auto Alt semantics");
        GpuiDisplayOptions symbols = new GpuiDisplayOptions(0, 0, GpuiDisplayOptions.ALT_SYMBOLS);
        check(symbols.altUsesSymbols(), "device symbol override is independent of profile");
        GpuiDisplayOptions terminal = new GpuiDisplayOptions(0, 0, GpuiDisplayOptions.ALT_TERMINAL);
        check(!terminal.altUsesSymbols(), "terminal Alt override is independent of profile");
        System.out.println("GPUI display policy: " + checks + " checks passed");
    }

    private static void equal(float expected, float actual, String label) {
        check(Float.compare(expected, actual) == 0, label + ": " + actual + " != " + expected);
    }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
