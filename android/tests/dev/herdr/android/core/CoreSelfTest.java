package dev.herdr.android.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLHandshakeException;

/** Dependency-free tests. Every HTTP peer is an in-memory fake; no user hosts are contacted. */
public final class CoreSelfTest {
    private static int assertions;
    private static final String TOKEN = "test-token-never-leak";

    interface Checked { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        hosts(); keys(); shortcuts(); quickCommands(); inputRouting(); transport(); cancellation();
        System.out.println("CoreSelfTest passed (" + assertions + " assertions)");
    }

    private static void hosts() throws Exception {
        HostProfile host = new HostProfile("  Laptop  ", "HTTPS://EXAMPLE.test:443/herdr", TOKEN);
        equal("Laptop", host.getName());
        equal("https://example.test/herdr/", host.getBaseUrl());
        equal(TOKEN, host.getToken());
        equal("https://example.test/herdr/v1/panes", host.resolve("v1/panes").toString());
        equal("https://example.test/herdr/v1/panes/w1%3Ap1/screen", host.resolve("v1/panes/w1%3Ap1/screen").toString());
        equal("https://example.test/herdr/v1/events?after=12&limit=50", host.resolve("v1/events?after=12&limit=50").toString());
        equal("https://[::1]:8443/", HostProfile.normalizeBaseUrl("https://[::1]:8443"));
        equal("https://example.test/a-b/a.b/", HostProfile.normalizeBaseUrl("https://example.test/a-b/a.b"));
        check(!host.toString().contains(TOKEN), "profile toString redacts credentials");
        String[] badHosts = { "http://example.test", "http://127.0.0.1", "https://", "ftp://example.test",
                "https://user:pass@example.test", "https://example.test?token=secret", "https://example.test#token=secret",
                "https://example.test/a/../b", "https://example.test/./", "https://example.test/%2e%2e/",
                "https://example.test/%252e%252e/", "https://example.test/a%2fb", "https://example.test//other",
                "https://example.test\\@evil.test", "https://example.test:0", "https://example.test:65536",
                "https://example.test:", "https://example.test\r\nHeader:value", "https://example.test/a b" };
        for (final String value : badHosts) illegal(new Checked() { public void run() { HostProfile.normalizeBaseUrl(value); } });
        for (final String value : new String[] { "", " leading", "trailing ", "x\r\ny", "x\ny", "x\ty", "é", repeat('a', 4097) }) {
            illegal(new Checked() { public void run() { HostProfile.validateToken(value); } });
        }
        for (final String value : new String[] { "https://evil.test/v1/panes", "//evil.test/v1/panes", "/v1/panes", "v1/../secret",
                "v1/%2e%2e/secret", "v1/%252e%252e/secret", "v1/panes/p%2fs/screen", "v1/panes/p%5cs/screen", "v1//panes",
                "v1/panes#secret", "v1/panes?url=https://evil.test", "v1/panes/p:1/screen", "v1/./panes", "hooks/claude" }) {
            illegal(new Checked() { public void run() { host.resolve(value); } });
        }
        illegal(new Checked() { public void run() { new HostProfile("bad\nname", "https://example.test", TOKEN); } });
    }

    private static void keys() throws Exception {
        equal(Arrays.asList("esc", "up", "enter"), KeyChord.parseSequence(" Escape, UP,Return "));
        equal(Arrays.asList("ctrl+c", "alt+x", "shift+tab"), KeyChord.parseSequence("Ctrl+C Alt+X Shift+Tab"));
        equal("ctrl+c", KeyChord.fromPhysical("C", true, false, false));
        equal("alt+x", KeyChord.fromPhysical("x", false, true, false));
        equal("shift+tab", KeyChord.fromPhysical("tab", false, false, true));
        for (String value : new String[] { "enter", "esc", "tab", "space", "backspace", "up", "down", "left", "right", "y", "n", "1", "9", "f1", "f12", "ctrl+a", "ctrl+z", "alt+a", "alt+z" }) equal(value, KeyChord.normalize(value));
        for (final String value : new String[] { "home", "end", "delete", "pageup", "pagedown", "insert", "f0", "f13", "ctrl+alt+c", "ctrl+space", "ctrl+[", "ctrl+\\", "ctrl+1", "cmd+c", "a", "0", "\u001b", "enter,,tab", "enter,", "enter tab,esc" }) {
            illegal(new Checked() { public void run() { KeyChord.parseSequence(value); } });
        }
        equal(16, KeyChord.validate(Collections.nCopies(16, "esc")).size());
        illegal(new Checked() { public void run() { KeyChord.validate(Collections.nCopies(17, "esc")); } });
        illegal(new Checked() { public void run() { KeyChord.fromPhysical("c", true, true, false); } });
        illegal(new Checked() { public void run() { KeyChord.fromPhysical("c", true, false, true); } });
        illegal(new Checked() { public void run() { KeyChord.fromPhysical("up", false, false, true); } });
        try { KeyChord.parseSequence("esc").add("enter"); throw new AssertionError("keys are mutable"); }
        catch (UnsupportedOperationException expected) { assertions++; }
    }

    private static void shortcuts() throws Exception {
        String source = "Stop = key:Ctrl+C\nMenu = keys:esc,up,enter\nUnicode = text:  λ猫😀\\nC:\\\\tmp\\tend  ";
        List<Shortcut> parsed = Shortcut.parseConfiguration(source);
        equal(3, parsed.size());
        equal("Stop", parsed.get(0).getLabel());
        equal("keys", parsed.get(0).getRouteSuffix());
        equal("{\"keys\":[\"ctrl+c\"]}", parsed.get(0).toJsonBody());
        equal("{\"keys\":[\"esc\",\"up\",\"enter\"]}", parsed.get(1).toJsonBody());
        equal("  λ猫😀\nC:\\tmp\tend  ", parsed.get(2).getText());
        equal("{\"text\":\"  λ猫😀\\nC:\\\\tmp\\tend  \"}", parsed.get(2).toJsonBody());
        equal(parsed.get(2).getText(), Shortcut.parseConfiguration(Shortcut.formatConfiguration(parsed)).get(2).getText());
        equal(8, Shortcut.parseConfiguration(Shortcut.DEFAULT_CONFIGURATION).size());
        equal(0, Shortcut.parseConfiguration("\n  \n").size());
        equal(" \t ", Shortcut.text("Spaces", " \t ").getText());
        equal("{\"text\":\"\\u001b[31m\"}", Shortcut.text("Escape", "\u001b[31m").toJsonBody());
        for (final String value : new String[] { "No assignment", "X = key:esc,enter", "X = key:home", "X = text:", "X = text:x\\",
                "X = text:x\\q", "X = key:esc\nX = key:tab", "X = unknown:enter", " = key:esc" }) {
            illegal(new Checked() { public void run() { Shortcut.parseConfiguration(value); } });
        }
        StringBuilder tooMany = new StringBuilder();
        for (int i = 0; i < 25; i++) tooMany.append("K").append(i).append(" = key:esc\n");
        final String many = tooMany.toString();
        illegal(new Checked() { public void run() { Shortcut.parseConfiguration(many); } });
        equal(65536, Shortcut.text("Full", repeat('a', 65536)).getText().length());
        illegal(new Checked() { public void run() { Shortcut.text("Too long", repeat('a', 65537)); } });
        illegal(new Checked() { public void run() { Shortcut.text("UTF-8 limit", repeat('猫', 22000)); } });
        illegal(new Checked() { public void run() { Shortcut.text("Broken Unicode", "\ud800"); } });
        illegal(new Checked() { public void run() { Shortcut.text("Bad=label", "a"); } });
        illegal(new Checked() { public void run() { Shortcut.parseConfiguration(repeat('x', Shortcut.MAX_CONFIGURATION_CHARS + 1)); } });
    }

    private static void quickCommands() throws Exception {
        QuickCommand command = new QuickCommand("  Inspect  ", "  /help λ猫😀\\n  ", true);
        equal("Inspect", command.getLabel());
        equal("  /help λ猫😀\\n  ", command.getText());
        check(command.isPinned(), "pinned state is explicit");
        check(!new QuickCommand("Help", "/help").isPinned(), "commands default to unpinned");
        equal(4096, new QuickCommand("Full", repeat('a', 4096)).getText().length());
        equal(2048, new QuickCommand("UTF-8 full", repeat('é', 2048)).getText().length());
        StringBuilder emoji = new StringBuilder();
        for (int i = 0; i < 1024; i++) emoji.append("😀");
        equal(2048, new QuickCommand("Supplementary full", emoji.toString()).getText().length());
        final String tooManyEmoji = emoji.append("😀").toString();
        illegal(new Checked() { public void run() { QuickCommand.validateText(tooManyEmoji); } });
        equal(40, new QuickCommand(repeat('a', 40), "x").getLabel().length());
        check(!new QuickCommand("Redacted", "secret-command").toString().contains("secret-command"), "command text is redacted");
        for (final String value : new String[] {null, "", repeat('a', 4097), repeat('é', 2049), "x\r", "x\n", "x\t",
                "x\u0000", "x\u001b", "x\u007f", "x\u0085", "x\u009f", "x\u2028", "x\u2029", "\ud800", "\udc00", "x\ud800y"}) {
            illegal(new Checked() { public void run() { new QuickCommand("Invalid", value); } });
        }
        for (final String value : new String[] {null, "", "  ", repeat('a', 41), "x\n", "x\r", "x\t", "x\u001b",
                "x\u0085", "x\u2028", "x\u2029", "\ud800", "\udc00"}) {
            illegal(new Checked() { public void run() { new QuickCommand(value, "/help"); } });
        }
        final List<QuickCommand> source = new ArrayList<QuickCommand>();
        source.add(command);
        List<QuickCommand> saved = QuickCommand.validatedCopy(source);
        source.clear();
        equal(1, saved.size());
        try { saved.clear(); throw new AssertionError("quick commands are mutable"); }
        catch (UnsupportedOperationException expected) { assertions++; }
        illegal(new Checked() { public void run() { QuickCommand.validatedCopy(null); } });
        illegal(new Checked() { public void run() { QuickCommand.validatedCopy(Collections.singletonList((QuickCommand) null)); } });
        illegal(new Checked() { public void run() { QuickCommand.validatedCopy(Arrays.asList(
                new QuickCommand("Repeat", "one"), new QuickCommand(" Repeat ", "two", true))); } });
        for (int i = 0; i < QuickCommand.MAX_COMMANDS; i++) source.add(new QuickCommand("C" + i, "/help"));
        equal(24, QuickCommand.validatedCopy(source).size());
        source.add(new QuickCommand("Overflow", "/help"));
        illegal(new Checked() { public void run() { QuickCommand.validatedCopy(source); } });
    }

    private static void transport() throws Exception {
        FakeConnection ok = new FakeConnection(200, "{\"panes\":[]}");
        Factory factory = new Factory(ok);
        CompanionClient client = client(factory);
        equal("{\"panes\":[]}", client.getPanes());
        equal("https://host.test/base/v1/panes", factory.url.toString());
        equal("Bearer " + TOKEN, ok.getRequestProperty("Authorization"));
        equal("application/json", ok.getRequestProperty("Accept"));
        equal("identity", ok.getRequestProperty("Accept-Encoding"));
        check(!ok.getInstanceFollowRedirects(), "redirects disabled");
        check(!ok.getUseCaches(), "caches disabled");
        equal(CompanionClient.CONNECT_TIMEOUT_MS, ok.getConnectTimeout());
        equal(CompanionClient.READ_TIMEOUT_MS, ok.getReadTimeout());
        check(ok.disconnected, "request connection cleaned up");
        client.close();
        kind(ClientException.Kind.CANCELLED, new Checked() { public void run() throws Exception { client.getPanes(); } });
        equal(1, factory.opens);

        FakeConnection post = new FakeConnection(200, "{\"ok\":true}");
        Factory postFactory = new Factory(post);
        client(postFactory).sendText("w1:p1", "  猫😀\n");
        equal("https://host.test/base/v1/panes/w1%3Ap1/text", postFactory.url.toString());
        equal("POST", post.getRequestMethod());
        equal("{\"text\":\"  猫😀\\n\"}", new String(post.sent.toByteArray(), StandardCharsets.UTF_8));
        equal(post.sent.size(), post.fixedLength());
        equal(1, postFactory.opens);
        FakeConnection start = new FakeConnection(200, "{}");
        client(new Factory(start)).post("v1/agents", "{}");
        equal(CompanionClient.START_READ_TIMEOUT_MS, start.getReadTimeout());
        Factory transcript = new Factory(new FakeConnection(200, "{}"));
        client(transcript).getTranscript("abc-123");
        equal("https://host.test/base/v1/sessions/abc-123/transcript?limit=200", transcript.url.toString());

        final Factory validationFactory = new Factory(new FakeConnection(200, "{}"));
        final CompanionClient validationClient = client(validationFactory);
        illegal(new Checked() { public void run() throws Exception { validationClient.sendText("../bad", "x"); } });
        illegal(new Checked() { public void run() throws Exception { validationClient.sendText("p_1", ""); } });
        illegal(new Checked() { public void run() throws Exception { validationClient.sendKeys("p_1", Arrays.asList("home")); } });
        illegal(new Checked() { public void run() throws Exception { validationClient.post("v1/panes", repeat('x', CompanionClient.MAX_REQUEST_BYTES + 1)); } });
        illegal(new Checked() { public void run() throws Exception { validationClient.get("https://evil.test"); } });
        equal(0, validationFactory.opens);

        for (int code : new int[] {301, 302, 303, 307, 308}) {
            final FakeConnection redirect = new FakeConnection(code, "secret " + TOKEN);
            final Factory redirectFactory = new Factory(redirect);
            final CompanionClient redirectClient = client(redirectFactory);
            ClientException error = kind(ClientException.Kind.REDIRECT, new Checked() { public void run() throws Exception { redirectClient.sendText("p_1", "x"); } });
            equal(code, error.getStatusCode());
            check(error.actionMayHaveApplied(), "redirect POST delivery treated as uncertain");
            equal(1, redirectFactory.opens);
            check(!redirect.bodyRead, "redirect body never consumed");
            check(!error.getMessage().contains(TOKEN), "redirect error redacted");
        }
        final FakeConnection denied = new FakeConnection(401, TOKEN);
        ClientException deniedError = kind(ClientException.Kind.HTTP, new Checked() { public void run() throws Exception { client(new Factory(denied)).getPanes(); } });
        equal(401, deniedError.getStatusCode());
        check(!denied.bodyRead && !deniedError.getMessage().contains(TOKEN), "remote error body redacted");
        check(deniedError.getCause() == null, "no sensitive nested causes");
        final FakeConnection advertised = new FakeConnection(200, "{}");
        advertised.declaredLength = CompanionClient.MAX_RESPONSE_BYTES + 1;
        kind(ClientException.Kind.RESPONSE_TOO_LARGE, new Checked() { public void run() throws Exception { client(new Factory(advertised)).getPanes(); } });
        check(!advertised.bodyRead, "oversize content length rejected before body read");
        final FakeConnection streamed = new FakeConnection(200, repeat('a', CompanionClient.MAX_RESPONSE_BYTES + 1));
        streamed.declaredLength = -1;
        kind(ClientException.Kind.RESPONSE_TOO_LARGE, new Checked() { public void run() throws Exception { client(new Factory(streamed)).getPanes(); } });
        check(streamed.disconnected, "oversize stream connection cleaned up");
        FakeConnection boundary = new FakeConnection(200, repeat('a', CompanionClient.MAX_RESPONSE_BYTES));
        equal(CompanionClient.MAX_RESPONSE_BYTES, client(new Factory(boundary)).getPanes().length());
        final FakeConnection invalid = new FakeConnection(200, "");
        invalid.received = new byte[] {(byte)0xc3, (byte)0x28};
        kind(ClientException.Kind.INVALID_RESPONSE, new Checked() { public void run() throws Exception { client(new Factory(invalid)).getPanes(); } });
        IOException[] errors = {new IOException("Token " + TOKEN), new SocketTimeoutException(TOKEN), new SSLHandshakeException(TOKEN)};
        ClientException.Kind[] kinds = {ClientException.Kind.NETWORK, ClientException.Kind.TIMEOUT, ClientException.Kind.TLS};
        for (int i = 0; i < errors.length; i++) {
            final FakeConnection failure = new FakeConnection(200, "{}");
            failure.failure = errors[i];
            final Factory noRetry = new Factory(failure);
            ClientException error = kind(kinds[i], new Checked() { public void run() throws Exception { client(noRetry).sendText("p1", "never replay"); } });
            check(error.actionMayHaveApplied(), "failed POST delivery uncertainty retained");
            check(!error.toString().contains(TOKEN), "platform error text redacted");
            equal(1, noRetry.opens);
        }
    }

    private static void inputRouting() {
        int accepted = 0;
        for (int mask = 0; mask < 128; mask++) {
            boolean enabled = (mask & 1) != 0;
            boolean screen = (mask & 2) != 0;
            boolean terminalFocused = (mask & 4) != 0;
            boolean editorFocused = (mask & 8) != 0;
            boolean physicalKeyboard = (mask & 16) != 0;
            boolean softKeyboard = (mask & 32) != 0;
            boolean dialogOpen = (mask & 64) != 0;
            boolean routed = InputRouting.shouldSendToTerminal(enabled, screen, terminalFocused,
                    editorFocused, physicalKeyboard, softKeyboard, dialogOpen);
            equal(mask == 23, routed);
            if (routed) accepted++;
        }
        equal(1, accepted);
    }

    private static void cancellation() throws Exception {
        final FakeConnection blocked = new FakeConnection(200, "{}");
        blocked.entered = new CountDownLatch(1);
        blocked.release = new CountDownLatch(1);
        final Factory factory = new Factory(blocked);
        final CompanionClient client = client(factory);
        final AtomicReference<Throwable> result = new AtomicReference<Throwable>();
        Thread worker = new Thread(new Runnable() { public void run() {
            try { client.getPanes(); result.set(new AssertionError("cancelled request succeeded")); }
            catch (Throwable error) { result.set(error); }
        } }, "core-self-test");
        worker.start();
        check(blocked.entered.await(3, TimeUnit.SECONDS), "request entered fake peer");
        kind(ClientException.Kind.BUSY, new Checked() { public void run() throws Exception { client.getAgents(); } });
        client.cancel();
        check(client.isClosed(), "cancel flag is immediate");
        check(!blocked.disconnected, "cancel signal performs no I/O");
        kind(ClientException.Kind.CANCELLED, new Checked() { public void run() throws Exception { client.sendText("p_1", "queued"); } });
        client.close();
        worker.join(3000);
        check(!worker.isAlive(), "cancelled worker stopped");
        check(result.get() instanceof ClientException, "cancellation returned typed failure");
        equal(ClientException.Kind.CANCELLED, ((ClientException) result.get()).getKind());
        check(client.isClosed(), "closed state persists");
        equal(1, factory.opens);
    }

    private static CompanionClient client(Factory factory) {
        return new CompanionClient(new HostProfile("Host", "https://host.test/base/", TOKEN), factory);
    }
    private static String repeat(char c, int count) { char[] values = new char[count]; Arrays.fill(values, c); return new String(values); }
    private static void equal(Object expected, Object actual) { check(expected.equals(actual), "expected " + expected + ", got " + actual); }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void illegal(Checked action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Expected IllegalArgumentException");
    }
    private static ClientException kind(ClientException.Kind expected, Checked action) throws Exception {
        try { action.run(); } catch (ClientException error) { equal(expected, error.getKind()); return error; }
        throw new AssertionError("Expected " + expected);
    }

    private static final class Factory implements CompanionClient.ConnectionFactory {
        final FakeConnection connection;
        int opens;
        URL url;
        Factory(FakeConnection connection) { this.connection = connection; }
        @Override public HttpURLConnection open(URL url) { this.url = url; opens++; return connection; }
    }
    private static final class FakeConnection extends HttpURLConnection {
        final int status;
        byte[] received;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        volatile boolean disconnected;
        boolean bodyRead;
        long declaredLength = -1;
        IOException failure;
        CountDownLatch entered;
        CountDownLatch release;
        FakeConnection(int status, String body) throws Exception {
            super(URI.create("https://host.test").toURL()); this.status = status; this.received = body.getBytes(StandardCharsets.UTF_8);
        }
        @Override public void disconnect() { disconnected = true; if (release != null) release.countDown(); }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        @Override public int getResponseCode() throws IOException {
            if (entered != null) entered.countDown();
            if (release != null) {
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Fake peer timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
                if (disconnected) throw new IOException("closed");
            }
            if (failure != null) throw failure;
            return status;
        }
        @Override public long getContentLengthLong() { return declaredLength; }
        @Override public InputStream getInputStream() { bodyRead = true; return new ByteArrayInputStream(received); }
        @Override public OutputStream getOutputStream() { return sent; }
        int fixedLength() { return fixedContentLength; }
    }
}
