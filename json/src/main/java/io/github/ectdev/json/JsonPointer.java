package io.github.ectdev.json;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * RFC 6901 JSON Pointer: a string such as {@code /Image/IDs/0} that names one value inside a document.
 *
 * <p>A pointer is empty (the whole document) or a sequence of {@code /}-prefixed tokens. Two characters have to be
 * escaped inside a token, because {@code /} separates tokens and {@code ~} introduces an escape: {@code ~0} stands for
 * {@code ~} and {@code ~1} for {@code /}.
 *
 * <p>The escapes are decoded by one left-to-right scan, which is not the same thing as two {@code replace} calls. The
 * RFC's wording is "first transform {@code ~1} to {@code /}, then {@code ~0} to {@code ~}", and the order matters: the
 * token {@code ~01} means the two characters {@code ~1}; replacing {@code ~0} first would turn it into {@code ~1} and
 * then into {@code /}. Scanning character by character gets it right without having to remember an order.
 *
 * <p>Two kinds of failure are kept apart. A pointer that is malformed (it does not start with {@code /}, or contains a
 * {@code ~} that is not followed by 0 or 1) is a programming error, and throws. A well-formed pointer that leads to
 * nothing (a missing member, an index past the end, a step into a number) is an ordinary answer, and yields an empty
 * {@code Optional}. An array token that is not a valid index (leading zeros, {@code -}, letters) leads nowhere: the RFC
 * reserves {@code -} for "the element after the last", which does not exist yet in a document being read.
 */
final class JsonPointer {

    private JsonPointer() { }

    static Optional<JsonValue> evaluate(JsonValue document, String pointer) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(pointer, "pointer");
        if (pointer.isEmpty()) {
            return Optional.of(document);
        }
        if (pointer.charAt(0) != '/') {
            throw new IllegalArgumentException("a JSON Pointer is empty or starts with '/': " + pointer);
        }
        // The limit -1 keeps trailing empty tokens: "/" is one empty token, the member named "".
        String[] tokens = pointer.substring(1).split("/", -1);
        // The whole pointer is decoded before the walk starts, so a malformed token is reported even when an earlier
        // step already leads nowhere: whether a pointer is well-formed must not depend on the document.
        for (int i = 0; i < tokens.length; i++) {
            tokens[i] = unescape(tokens[i]);
        }
        JsonValue current = document;
        for (String token : tokens) {
            current = step(current, token);
            if (current == null) {
                return Optional.empty();
            }
        }
        return Optional.of(current);
    }

    /** One step into {@code value}, or {@code null} if it has no such child. */
    private static JsonValue step(JsonValue value, String token) {
        return switch (value) {
            case JsonObject(Map<String, JsonValue> members) -> members.get(token);
            case JsonArray(List<JsonValue> items) -> {
                int index = arrayIndex(token);
                yield index >= 0 && index < items.size() ? items.get(index) : null;
            }
            case JsonNull scalar -> null;
            case JsonBoolean scalar -> null;
            case JsonNumber scalar -> null;
            case JsonString scalar -> null;
        };
    }

    private static String unescape(String token) {
        if (token.indexOf('~') < 0) {
            return token;
        }
        StringBuilder out = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c != '~') {
                out.append(c);
                continue;
            }
            char next = i + 1 < token.length() ? token.charAt(i + 1) : '\0';
            if (next == '0') {
                out.append('~');
            } else if (next == '1') {
                out.append('/');
            } else {
                throw new IllegalArgumentException(
                    "invalid escape in JSON Pointer token \"" + token + "\": '~' must be followed by 0 or 1");
            }
            i++; // the escape's second character is consumed
        }
        return out.toString();
    }

    /** The index a token names, or -1 if it is not a valid array index: {@code 0} or digits without a leading zero. */
    private static int arrayIndex(String token) {
        int length = token.length();
        if (length == 0 || length > 9) { // nine digits stay below Integer.MAX_VALUE; longer cannot be an index
            return -1;
        }
        if (token.charAt(0) == '0') {
            return length == 1 ? 0 : -1;
        }
        int value = 0;
        for (int i = 0; i < length; i++) {
            char c = token.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }
}
