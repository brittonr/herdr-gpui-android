package dev.herdr.android.core;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Small JSON string writer; transport deliberately leaves object parsing to Android's org.json. */
final class JsonStrings {
    private JsonStrings() { }

    static byte[] utf8(String value) {
        if (value == null) throw new IllegalArgumentException("Text is required");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Text contains an incomplete Unicode character");
        }
    }

    static String quote(String value) {
        utf8(value);
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        out.append("\\u00");
                        out.append("0123456789abcdef".charAt((c >> 4) & 15));
                        out.append("0123456789abcdef".charAt(c & 15));
                    } else out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
