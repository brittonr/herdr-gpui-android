package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BannerCopyTest {
    @Test
    public void transport_wording_becomes_a_sentence() {
        assertEquals("Connection lost · the host stopped answering",
                BannerCopy.human("endpoint health check timed out"));
        assertEquals("Connection lost · the host timed out",
                BannerCopy.human("read timed out"));
        assertEquals("Connection lost", BannerCopy.human("connection ended"));
        assertEquals("Connection lost", BannerCopy.human("socket closed"));
        assertEquals("Connection lost", BannerCopy.human("disconnected"));
    }

    @Test
    public void an_exited_shell_is_not_a_lost_connection() {
        assertEquals("The shell exited", BannerCopy.human(BannerCopy.SHELL_EXITED));
        // Only the exact status: a transport reason that mentions a shell stays a loss.
        assertEquals("Connection lost", BannerCopy.human("shell channel closed"));
    }

    @Test
    public void empty_and_unknown_reasons_stay_useful() {
        assertEquals("Connection lost", BannerCopy.human(null));
        assertEquals("Connection lost", BannerCopy.human(""));
        assertEquals("Connection lost · something odd", BannerCopy.human("something odd"));
    }

    @Test
    public void unknown_reason_is_bounded() {
        StringBuilder longReason = new StringBuilder();
        for (int i = 0; i < 500; i++) longReason.append('x');
        String human = BannerCopy.human(longReason.toString());
        assertTrue(human.length() <= "Connection lost · ".length() + 120);
    }
}
