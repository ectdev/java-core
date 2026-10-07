package io.github.ectdev.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A strict JSON parser: a recursive-descent reader for RFC 8259, one method per rule of the grammar.
 *
 * <p>"Strict" means it accepts what the RFC accepts and nothing more. There are no comments, no trailing commas, no
 * single-quoted strings, no unquoted names, no {@code NaN} or {@code Infinity}, no leading zeros or leading plus
 * signs, and no whitespace other than space, tab, line feed and carriage return. Lenient parsers are convenient and
 * are also how two systems come to disagree about what the same bytes mean.
 *
 * <h2>Decisions the RFC leaves open</h2>
 * <ul>
 *   <li><b>Duplicate member names are an error.</b> RFC 8259 §4 says the behaviour is "unpredictable": some parsers
 *       keep the first, some the last, some fail. Picking one silently is how a request gets two different readings
 *       in two services, so this parser refuses.</li>
 *   <li><b>Nesting is limited</b> (default {@value #DEFAULT_MAX_DEPTH}). The parser is recursive, so a document of a
 *       million {@code [} would otherwise overflow the stack, and a {@code StackOverflowError} is not something a
 *       caller of a parser should have to expect. §9 explicitly allows an implementation to set such a limit.</li>
 *   <li><b>An unpaired surrogate escape is an error</b> (the escape for a lone high or low half of a character outside the Basic
 *       Multilingual Plane). It is valid JSON grammar but
 *       not valid Unicode, and it has no UTF-8 encoding.</li>
 *   <li><b>Numbers keep their digits.</b> They become {@link BigDecimal}, never {@code double}; see {@link JsonNumber}.</li>
 * </ul>
 *
 * <p>The parser reads a {@code String}, so decoding bytes to text (JSON is UTF-8 by RFC 8259 §8.1) is the caller's
 * job. A leading byte-order mark is therefore not whitespace and is rejected, as the RFC says generators must not
 * emit one.
 */
public final class JsonParser {

    /** Nesting limit used by {@link #parse(String)}: deep enough for any realistic document, far below the stack. */
    public static final int DEFAULT_MAX_DEPTH = 512;

    private final String text;
    private final int maxDepth;
    private int pos;

    private JsonParser(String text, int maxDepth) {
        this.text = text;
        this.maxDepth = maxDepth;
    }

    /** Parses one JSON value, with at most {@link #DEFAULT_MAX_DEPTH} levels of nesting. */
    public static JsonValue parse(String text) {
        return parse(text, DEFAULT_MAX_DEPTH);
    }

    /**
     * @param maxDepth how many arrays and objects may be nested inside each other, at least 1; {@code [[1]]} is depth 2
     * @throws JsonParseException if {@code text} is not valid JSON, or is nested deeper than {@code maxDepth}
     */
    public static JsonValue parse(String text, int maxDepth) {
        Objects.requireNonNull(text, "text");
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be at least 1: " + maxDepth);
        }
        JsonParser parser = new JsonParser(text, maxDepth);
        parser.skipWhitespace();
        JsonValue value = parser.parseValue(0);
        parser.skipWhitespace();
        if (parser.pos < text.length()) {
            throw parser.error("unexpected " + describe(text.charAt(parser.pos)) + " after the end of the value");
        }
        return value;
    }

    // ------------------------------------------------------------------ the grammar, one method per rule

    private JsonValue parseValue(int depth) {
        if (pos >= text.length()) {
            throw error("unexpected end of input, expected a value");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> parseObject(depth + 1);
            case '[' -> parseArray(depth + 1);
            case '"' -> new JsonString(parseString());
            case 't' -> literal("true", JsonBoolean.TRUE);
            case 'f' -> literal("false", JsonBoolean.FALSE);
            case 'n' -> literal("null", JsonNull.INSTANCE);
            default -> {
                if (c == '-' || isDigit(c)) {
                    yield parseNumber();
                }
                throw error("unexpected " + describe(c) + ", expected a value");
            }
        };
    }

    private JsonValue parseArray(int depth) {
        requireDepth(depth);
        pos++; // the opening bracket
        skipWhitespace();
        List<JsonValue> items = new ArrayList<>();
        if (peek() == ']') {
            pos++;
            return new JsonArray(items);
        }
        while (true) {
            items.add(parseValue(depth));
            skipWhitespace();
            char c = requireMore("',' or ']'");
            if (c == ']') {
                pos++;
                return new JsonArray(items);
            }
            if (c != ',') {
                throw error("unexpected " + describe(c) + ", expected ',' or ']'");
            }
            pos++;
            skipWhitespace();
        }
    }

    private JsonValue parseObject(int depth) {
        requireDepth(depth);
        pos++; // the opening brace
        skipWhitespace();
        Map<String, JsonValue> members = new LinkedHashMap<>();
        if (peek() == '}') {
            pos++;
            return new JsonObject(members);
        }
        while (true) {
            if (peek() != '"') {
                throw error(pos >= text.length()
                    ? "unexpected end of input, expected a member name"
                    : "unexpected " + describe(text.charAt(pos)) + ", expected a member name (a string)");
            }
            int nameStart = pos;
            String name = parseString();
            skipWhitespace();
            if (requireMore("':'") != ':') {
                throw error("unexpected " + describe(text.charAt(pos)) + ", expected ':' after the member name");
            }
            pos++;
            skipWhitespace();
            JsonValue value = parseValue(depth);
            if (members.put(name, value) != null) {
                throw errorAt(nameStart, "duplicate member name " + quote(name) + " (RFC 8259 leaves this undefined; it is refused here)");
            }
            skipWhitespace();
            char c = requireMore("',' or '}'");
            if (c == '}') {
                pos++;
                return new JsonObject(members);
            }
            if (c != ',') {
                throw error("unexpected " + describe(c) + ", expected ',' or '}'");
            }
            pos++;
            skipWhitespace();
        }
    }

    /** Reads a string starting at the opening quote and leaves {@code pos} after the closing one. */
    private String parseString() {
        pos++; // the opening quote
        StringBuilder out = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error("unexpected end of input inside a string");
            }
            char c = text.charAt(pos);
            if (c == '"') {
                pos++;
                return out.toString();
            }
            if (c == '\\') {
                parseEscape(out);
            } else if (c < 0x20) {
                throw error("unescaped control character U+%04X inside a string; write it as an escape".formatted((int) c));
            } else if (Character.isHighSurrogate(c)) {
                if (pos + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(pos + 1))) {
                    throw error("unpaired surrogate U+%04X".formatted((int) c));
                }
                out.append(c).append(text.charAt(pos + 1));
                pos += 2;
            } else if (Character.isLowSurrogate(c)) {
                throw error("unpaired surrogate U+%04X".formatted((int) c));
            } else {
                out.append(c);
                pos++;
            }
        }
    }

    private void parseEscape(StringBuilder out) {
        int backslash = pos;
        pos++;
        if (pos >= text.length()) {
            throw error("unexpected end of input after a backslash");
        }
        char escape = text.charAt(pos);
        pos++;
        switch (escape) {
            case '"' -> out.append('"');
            case '\\' -> out.append('\\');
            case '/' -> out.append('/');
            case 'b' -> out.append('\b');
            case 'f' -> out.append('\f');
            case 'n' -> out.append('\n');
            case 'r' -> out.append('\r');
            case 't' -> out.append('\t');
            case 'u' -> parseUnicodeEscape(out, backslash);
            default -> throw errorAt(backslash, "invalid escape sequence \\" + escape);
        }
    }

    /** Reads the four hex digits after {@code \}{@code u}, joining a surrogate pair written as two escapes. */
    private void parseUnicodeEscape(StringBuilder out, int backslash) {
        char unit = (char) hex4(backslash);
        if (Character.isHighSurrogate(unit)) {
            // The only valid continuation is another escape holding the low half.
            if (pos + 1 < text.length() && text.charAt(pos) == '\\' && text.charAt(pos + 1) == 'u') {
                int secondBackslash = pos;
                pos += 2;
                char second = (char) hex4(secondBackslash);
                if (Character.isLowSurrogate(second)) {
                    out.append(unit).append(second);
                    return;
                }
            }
            throw errorAt(backslash, "unpaired surrogate escape \\u%04x: a high surrogate must be followed by a low-surrogate escape".formatted((int) unit));
        }
        if (Character.isLowSurrogate(unit)) {
            throw errorAt(backslash, "unpaired surrogate escape \\u%04x: a low surrogate must follow a high surrogate".formatted((int) unit));
        }
        out.append(unit);
    }

    private int hex4(int escapeStart) {
        if (pos + 4 > text.length()) {
            throw errorAt(escapeStart, "an escape \\u must be followed by four hexadecimal digits");
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = hexValue(text.charAt(pos + i));
            if (digit < 0) {
                throw errorAt(pos + i, "unexpected " + describe(text.charAt(pos + i)) + " in a \\u escape, expected a hexadecimal digit");
            }
            value = value * 16 + digit;
        }
        pos += 4;
        return value;
    }

    private JsonValue parseNumber() {
        int start = pos;
        if (text.charAt(pos) == '-') {
            pos++;
        }
        if (pos >= text.length() || !isDigit(text.charAt(pos))) {
            throw error(pos >= text.length() ? "unexpected end of input, expected a digit" : "unexpected " + describe(text.charAt(pos)) + ", expected a digit");
        }
        if (text.charAt(pos) == '0') {
            pos++;
            if (pos < text.length() && isDigit(text.charAt(pos))) {
                throw error("leading zeros are not allowed in a number");
            }
        } else {
            skipDigits();
        }
        if (pos < text.length() && text.charAt(pos) == '.') {
            pos++;
            requireDigits("after the decimal point");
        }
        if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
            pos++;
            if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                pos++;
            }
            requireDigits("in the exponent");
        }
        try {
            return new JsonNumber(new BigDecimal(text.substring(start, pos)));
        } catch (NumberFormatException e) {
            throw errorAt(start, "number out of range: its exponent does not fit in an int");
        }
    }

    private JsonValue literal(String word, JsonValue value) {
        if (!text.startsWith(word, pos)) {
            throw error("expected '" + word + "'");
        }
        pos += word.length();
        return value;
    }

    // ------------------------------------------------------------------ helpers

    private void requireDepth(int depth) {
        if (depth > maxDepth) {
            throw error("nesting is deeper than the limit of " + maxDepth + " levels");
        }
    }

    private void requireDigits(String where) {
        if (pos >= text.length() || !isDigit(text.charAt(pos))) {
            throw error((pos >= text.length() ? "unexpected end of input" : "unexpected " + describe(text.charAt(pos)))
                + ", expected a digit " + where);
        }
        skipDigits();
    }

    private void skipDigits() {
        while (pos < text.length() && isDigit(text.charAt(pos))) {
            pos++;
        }
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    /** The current character, or NUL at the end of the input (a position {@link #requireMore} reports properly). */
    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private char requireMore(String expected) {
        if (pos >= text.length()) {
            throw error("unexpected end of input, expected " + expected);
        }
        return text.charAt(pos);
    }

    private JsonParseException error(String problem) {
        return new JsonParseException(problem, text, pos);
    }

    private JsonParseException errorAt(int offset, String problem) {
        return new JsonParseException(problem, text, offset);
    }

    /** ASCII digits only: {@code Character.isDigit} also accepts the digits of other scripts, which JSON does not. */
    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** ASCII hexadecimal digits only, for the same reason: {@code Character.digit} accepts fullwidth digits. */
    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static String describe(char c) {
        return c >= 0x20 && c < 0x7f ? "character '" + c + "'" : "character U+%04X".formatted((int) c);
    }

    private static String quote(String name) {
        return "\"" + (name.length() > 40 ? name.substring(0, 40) + "…" : name) + "\"";
    }
}
