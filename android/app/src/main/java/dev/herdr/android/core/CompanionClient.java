package dev.herdr.android.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.net.ssl.SSLException;

/** Blocking single-flight transport. Call only on a worker; close when changing host or stopping UI. */
public final class CompanionClient implements AutoCloseable {
    public static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    public static final int MAX_REQUEST_BYTES = 512 * 1024;
    public static final int CONNECT_TIMEOUT_MS = 10000;
    public static final int READ_TIMEOUT_MS = 15000;
    public static final int START_READ_TIMEOUT_MS = 45000;
    public static final int RESPONSE_READ_BUDGET_MS = 30000;

    interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }

    private final HostProfile host;
    private final ConnectionFactory connections;
    private final Object lock = new Object();
    private HttpURLConnection active;
    private boolean closed;

    public CompanionClient(HostProfile host) {
        this(host, new ConnectionFactory() {
            @Override public HttpURLConnection open(URL url) throws IOException {
                return (HttpURLConnection) url.openConnection();
            }
        });
    }

    CompanionClient(HostProfile host, ConnectionFactory connections) {
        if (host == null || connections == null) throw new IllegalArgumentException("Host is required");
        this.host = host;
        this.connections = connections;
    }

    public String get(String relativePath) throws ClientException { return request("GET", relativePath, null); }
    public String post(String relativePath, String json) throws ClientException { return request("POST", relativePath, json); }
    public String getAgents() throws ClientException { return get("v1/agents"); }
    public String getPanes() throws ClientException { return get("v1/panes"); }
    public String getTranscript(String sessionId) throws ClientException {
        return get("v1/sessions/" + HostProfile.requireId(sessionId) + "/transcript?limit=200");
    }
    public String getScreen(String paneId) throws ClientException { return get(paneRoute(paneId, "screen")); }
    public String sendText(String paneId, String text) throws ClientException {
        Shortcut.validateText(text);
        return post(paneRoute(paneId, "text"), "{\"text\":" + JsonStrings.quote(text) + "}");
    }
    public String sendPrompt(String paneId, String text) throws ClientException {
        Shortcut.validateText(text);
        if (text.trim().isEmpty()) throw new IllegalArgumentException("Prompt must not be blank");
        return post(paneRoute(paneId, "prompt"), "{\"text\":" + JsonStrings.quote(text) + "}");
    }
    public String sendKeys(String paneId, List<String> keys) throws ClientException {
        List<String> checked = KeyChord.validate(keys);
        StringBuilder out = new StringBuilder("{\"keys\":[");
        for (int i = 0; i < checked.size(); i++) {
            if (i > 0) out.append(',');
            out.append(JsonStrings.quote(checked.get(i)));
        }
        return post(paneRoute(paneId, "keys"), out.append("]}").toString());
    }
    public String sendShortcut(String paneId, Shortcut shortcut) throws ClientException {
        if (shortcut == null) throw new IllegalArgumentException("Shortcut is required");
        return post(paneRoute(paneId, shortcut.getRouteSuffix()), shortcut.toJsonBody());
    }

    private static String paneRoute(String id, String action) {
        return "v1/panes/" + HostProfile.requireId(id) + "/" + action;
    }

    public String request(String method, String relativePath, String json) throws ClientException {
        if (!"GET".equals(method) && !"POST".equals(method)) {
            throw new IllegalArgumentException("Only GET and POST are supported");
        }
        final boolean mutation = "POST".equals(method);
        if (mutation != (json != null)) throw new IllegalArgumentException("Only POST requires a JSON body");
        if (json != null && json.length() > MAX_REQUEST_BYTES) throw new IllegalArgumentException("Request is too large");
        final byte[] body = json == null ? null : JsonStrings.utf8(json);
        if (body != null && body.length > MAX_REQUEST_BYTES) throw new IllegalArgumentException("Request is too large");
        final URL url = host.resolve(relativePath);
        HttpURLConnection connection = null;
        boolean attempted = false;
        int status = 0;
        try {
            synchronized (lock) {
                if (closed) throw failure(ClientException.Kind.CANCELLED, "Connection is closed", 0, false);
                if (active != null) throw failure(ClientException.Kind.BUSY, "Another request is still running", 0, false);
                connection = connections.open(url);
                active = connection;
            }
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(mutation && "v1/agents".equals(relativePath)
                    ? START_READ_TIMEOUT_MS : READ_TIMEOUT_MS);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Authorization", "Bearer " + host.getToken());
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Cache-Control", "no-store");
            if (mutation) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                // Streaming bodies are not buffered/replayed for redirects or HTTP authentication.
                connection.setFixedLengthStreamingMode(body.length);
                ensureOpen(false);
                attempted = true;
                try (OutputStream stream = connection.getOutputStream()) {
                    ensureOpen(true);
                    stream.write(body);
                }
            }
            ensureOpen(mutation && attempted);
            attempted = true;
            status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                throw failure(ClientException.Kind.REDIRECT, "Redirect refused. Use the host's final HTTPS URL", status, mutation);
            }
            if (status < 200 || status >= 300) {
                String message = status == 401 || status == 403
                        ? "Authentication failed. Check this host's token"
                        : "Host rejected the request (HTTP " + status + ")";
                throw failure(ClientException.Kind.HTTP, message, status, mutation);
            }
            if (connection.getContentLengthLong() > MAX_RESPONSE_BYTES) {
                throw failure(ClientException.Kind.RESPONSE_TOO_LARGE, "Host response exceeded the 1 MiB limit", status, mutation);
            }
            byte[] response;
            try (InputStream stream = connection.getInputStream()) {
                response = readBounded(stream, status, mutation);
            }
            ensureOpen(mutation);
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response)).toString();
            } catch (CharacterCodingException e) {
                throw failure(ClientException.Kind.INVALID_RESPONSE, "Host returned invalid UTF-8", status, mutation);
            }
        } catch (ClientException e) {
            throw e;
        } catch (IOException e) {
            if (isClosed()) throw failure(ClientException.Kind.CANCELLED, "Request cancelled", status, mutation && attempted);
            if (e instanceof SSLException) {
                throw failure(ClientException.Kind.TLS, "TLS connection failed. Check the host's trusted HTTPS certificate", status, mutation && attempted);
            }
            if (e instanceof SocketTimeoutException) {
                throw failure(ClientException.Kind.TIMEOUT, "Connection timed out", status, mutation && attempted);
            }
            throw failure(ClientException.Kind.NETWORK, "Could not reach this host", status, mutation && attempted);
        } finally {
            if (connection != null) {
                connection.disconnect();
                synchronized (lock) { if (active == connection) active = null; }
            }
        }
    }

    private byte[] readBounded(InputStream stream, int status, boolean mutation) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long started = System.nanoTime();
        int read;
        while ((read = stream.read(buffer)) != -1) {
            ensureOpen(mutation);
            // A trickle of bytes must not reset the socket's idle timeout forever.
            if (System.nanoTime() - started > RESPONSE_READ_BUDGET_MS * 1000000L) {
                throw new SocketTimeoutException("Response time budget exhausted");
            }
            if (read > MAX_RESPONSE_BYTES - out.size()) {
                throw failure(ClientException.Kind.RESPONSE_TOO_LARGE, "Host response exceeded the 1 MiB limit", status, mutation);
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private void ensureOpen(boolean actionMayHaveApplied) throws ClientException {
        if (isClosed()) throw failure(ClientException.Kind.CANCELLED, "Request cancelled", 0, actionMayHaveApplied);
    }

    public boolean isClosed() { synchronized (lock) { return closed; } }

    /** Immediate cancellation signal with no I/O. Safe on the main thread; close off-main afterward. */
    public void cancel() { synchronized (lock) { closed = true; } }

    /** Permanently cancels this client. A newly selected host must have its own client instance. */
    @Override public void close() {
        HttpURLConnection connection;
        synchronized (lock) {
            closed = true;
            connection = active;
        }
        if (connection != null) connection.disconnect();
    }

    private static ClientException failure(ClientException.Kind kind, String message, int status, boolean uncertain) {
        if (uncertain) message += ". The action may already have reached the host; refresh before retrying";
        return new ClientException(kind, message, status, uncertain);
    }
}
