package io.github.ectdev.json;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes a {@link JsonValue} as JSON text, compact or indented.
 *
 * <p>The central {@code switch} has one case per kind of value and no {@code default}. That is deliberate: because
 * {@link JsonValue} is sealed, the compiler checks the switch is exhaustive, so a kind of value added later cannot be
 * forgotten here.
 *
 * <p>Strings are escaped as little as the grammar allows and no more: the quote, the backslash and the control
 * characters below U+0020 (as {@code \n}, {@code \t} and so on where a short form exists, a backslash, the letter u and four hex digits
 * otherwise).
 * Everything else, including characters outside ASCII and outside the Basic Multilingual Plane, is written as it is,
 * so the output is as readable as the input and is meant to be encoded as UTF-8. Numbers use
 * {@link BigDecimal#toString()}, which may give exponent notation ({@code 1E+3}); that is valid JSON and reads back as
 * the same {@code BigDecimal}.
 */
public final class JsonWriter {

    private JsonWriter() { }

    /** Compact text: no whitespace outside strings. */
    public static String write(JsonValue value) {
        return write(value, 0);
    }

    /**
     * @param indent spaces per nesting level; 0 gives compact text. Empty arrays and objects stay on one line
     *               ({@code []} and {@code {}}).
     */
    public static String write(JsonValue value, int indent) {
        Objects.requireNonNull(value, "value");
        if (indent < 0) {
            throw new IllegalArgumentException("indent must not be negative: " + indent);
        }
        StringBuilder out = new StringBuilder();
        append(out, value, indent, 0);
        return out.toString();
    }

    private static void append(StringBuilder out, JsonValue value, int indent, int level) {
        switch (value) {
            case JsonNull ignored -> out.append("null");
            case JsonBoolean(boolean flag) -> out.append(flag);
            case JsonNumber(BigDecimal number) -> out.append(number.toString());
            case JsonString(String text) -> appendString(out, text);
            case JsonArray(List<JsonValue> items) -> appendArray(out, items, indent, level);
            case JsonObject(Map<String, JsonValue> members) -> appendObject(out, members, indent, level);
        }
    }

    private static void appendArray(StringBuilder out, List<JsonValue> items, int indent, int level) {
        if (items.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[');
        boolean first = true;
        for (JsonValue item : items) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newline(out, indent, level + 1);
            append(out, item, indent, level + 1);
        }
        newline(out, indent, level);
        out.append(']');
    }

    private static void appendObject(StringBuilder out, Map<String, JsonValue> members, int indent, int level) {
        if (members.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, JsonValue> member : members.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newline(out, indent, level + 1);
            appendString(out, member.getKey());
            out.append(indent > 0 ? ": " : ":");
            append(out, member.getValue(), indent, level + 1);
        }
        newline(out, indent, level);
        out.append('}');
    }

    private static void newline(StringBuilder out, int indent, int level) {
        if (indent == 0) {
            return;
        }
        out.append('\n');
        out.repeat(' ', indent * level);
    }

    private static void appendString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
