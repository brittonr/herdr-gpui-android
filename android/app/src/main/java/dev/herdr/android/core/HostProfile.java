package dev.herdr.android.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.MalformedURLException;
import java.util.Locale;

/** An immutable, validated HTTPS origin and optional reverse-proxy base path. */
public final class HostProfile {
    private final String name;
    private final String baseUrl;
    private final String token;

    public HostProfile(String name, String baseUrl, String token) {
        if (name == null || name.trim().isEmpty() || name.length() > 80 || hasControl(name)) {
            throw new IllegalArgumentException("Host name must contain 1–80 characters on one line");
        }
        this.name = name.trim();
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.token = validateToken(token);
    }

    public String getName() { return name; }
    public String getBaseUrl() { return baseUrl; }
    /** Only use for authenticated transport and encrypted storage; never log this value. */
    public String getToken() { return token; }

    public static String validateToken(String value) {
        if (value == null || value.isEmpty() || value.length() > 4096) {
            throw new IllegalArgumentException("Enter a token containing 1–4096 visible ASCII characters");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                throw new IllegalArgumentException("Token must not contain spaces, control characters or non-ASCII text");
            }
        }
        return value;
    }

    public static String normalizeBaseUrl(String value) {
        if (value == null || value.length() > 2048 || hasControl(value)) {
            throw new IllegalArgumentException("Enter an HTTPS host URL");
        }
        try {
            URI uri = new URI(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Use HTTPS without credentials, query parameters or a fragment");
            }
            if (uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getRawAuthority().endsWith(":")) {
                throw new IllegalArgumentException("Host port must be between 1 and 65535");
            }
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            // Do not let proxies reinterpret encoded separators or dot segments.
            if (!path.matches("/[A-Za-z0-9._~/-]*") || path.contains("//")) {
                throw new IllegalArgumentException("Base path must use plain URL-safe segments without encoded characters");
            }
            for (String segment : path.split("/", -1)) {
                if (segment.equals(".") || segment.equals("..")) {
                    throw new IllegalArgumentException("Base path must not contain dot segments");
                }
            }
            if (!path.endsWith("/")) path += "/";
            int port = uri.getPort() == 443 ? -1 : uri.getPort();
            return new URI("https", null, uri.getHost().toLowerCase(Locale.ROOT), port,
                    path, null, null).toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Enter a valid HTTPS host URL");
        }
    }

    /** Relative API routes cannot replace the configured origin or escape its base path. */
    public URL resolve(String relativePath) {
        if (relativePath == null || relativePath.length() > 2048
                || !relativePath.matches("v1/[A-Za-z0-9_-]+(?:/(?:[A-Za-z0-9_-]|%3[Aa])+)*(?:\\?[A-Za-z0-9_-]+=[A-Za-z0-9_-]+(?:&[A-Za-z0-9_-]+=[A-Za-z0-9_-]+)*)?")) {
            throw new IllegalArgumentException("Invalid relative API route");
        }
        try {
            return URI.create(baseUrl + relativePath).toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid API URL");
        }
    }

    static String requireId(String value) {
        if (value == null || value.length() > 128 || !value.matches("[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)*")) {
            throw new IllegalArgumentException("Invalid pane or session identifier");
        }
        return value.replace(":", "%3A");
    }

    static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }

    @Override public String toString() { return "HostProfile{" + name + ", credentials redacted}"; }
}
