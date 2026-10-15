package dev.herdr.android.core;

import java.io.IOException;

/** Safe diagnostics never expose credentials, request/response bodies, URLs or nested platform errors. */
public final class ClientException extends IOException {
    private static final long serialVersionUID = 1L;
    public enum Kind { NETWORK, TIMEOUT, TLS, HTTP, REDIRECT, RESPONSE_TOO_LARGE, INVALID_RESPONSE, CANCELLED, BUSY }

    private final Kind kind;
    private final int statusCode;
    private final boolean actionMayHaveApplied;

    ClientException(Kind kind, String message, int statusCode, boolean actionMayHaveApplied) {
        super(message);
        this.kind = kind;
        this.statusCode = statusCode;
        this.actionMayHaveApplied = actionMayHaveApplied;
    }

    public Kind getKind() { return kind; }
    /** Zero when no HTTP response was received. */
    public int getStatusCode() { return statusCode; }
    public boolean actionMayHaveApplied() { return actionMayHaveApplied; }
}
