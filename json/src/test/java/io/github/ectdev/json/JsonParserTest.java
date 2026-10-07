package io.github.ectdev.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Written from RFC 8259, not from the parser. The accepted documents are worked out by hand, and every rejected
 * one comes with the line and column at which the error must be reported, also worked out by hand by counting
 * characters in the text.
 *
 * <p>Test sources avoid the three-character sequence backslash-u: Java reads it as a Unicode escape even inside
 * comments. JSON escapes are written with a doubled backslash, and characters that are awkward to type are built
 * from code points.
 */
class JsonParserTest {

    private static JsonValue parse(String text) {
        return JsonParser.parse(text);
    }

    private static JsonNumber num(String decimal) {
        return JsonNumber.of(decimal);
    }

    private static JsonString str(String text) {
        return new JsonString(text);
    }

    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    // ------------------------------------------------------------------ what must be accepted

    static Stream<Arguments> scalars() {
        return Stream.of(
            Arguments.of("null", JsonNull.INSTANCE),
            Arguments.of("true", JsonBoolean.TRUE),
            Arguments.of("false", JsonBoolean.FALSE),
            Arguments.of("0", num("0")),
            Arguments.of("-0", num("0")),
            Arguments.of("42", num("42")),
            Arguments.of("-17", num("-17")),
            Arguments.of("3.14", num("3.14")),
            Arguments.of("0.5", num("0.5")),
            Arguments.of("0.0", num("0.0")),
            Arguments.of("1e5", num("1e5")),
            Arguments.of("1E5", num("1E5")),
            Arguments.of("1e+5", num("1e+5")),
            Arguments.of("1e-5", num("1e-5")),
            Arguments.of("-1.5e10", num("-1.5e10")),
            Arguments.of("0e0", num("0e0")),
            Arguments.of("\"\"", str("")),
            Arguments.of("\"abc\"", str("abc")),
            Arguments.of("\"with space\"", str("with space")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scalars")
    void acceptsEveryScalarThatTheGrammarAllows(String text, JsonValue expected) {
        assertEquals(expected, parse(text));
    }

    @Test
    void acceptsOnlyTheFourWhitespaceCharactersBetweenTokens() {
        assertEquals(JsonArray.of(num("1"), num("2")), parse(" \t\r\n[ \t1 ,\r\n2\n ]\t \n"));
        assertEquals(JsonObject.of("a", num("1")), parse("{\n\t\"a\"\r\n:\t1\n}"));
    }

    @Test
    void emptyContainersAndNesting() {
        assertEquals(new JsonArray(List.of()), parse("[]"));
        assertEquals(new JsonObject(java.util.Map.of()), parse("{}"));
        assertEquals(new JsonArray(List.of()), parse("[ ]"));
        assertEquals(JsonArray.of(new JsonArray(List.of()), JsonArray.of(num("1"))), parse("[[],[1]]"));
        assertEquals(JsonObject.of("a", JsonObject.of("b", JsonArray.of(JsonNull.INSTANCE))), parse("{\"a\":{\"b\":[null]}}"));
    }

    /** Section 13 of RFC 8259, verbatim. */
    @Test
    void parsesTheFirstExampleOfTheRfc() {
        String text = """
            {
              "Image": {
                  "Width":  800,
                  "Height": 600,
                  "Title":  "View from 15th Floor",
                  "Thumbnail": {
                      "Url":    "http://www.example.com/image/481989943",
                      "Height": 125,
                      "Width":  100
                  },
                  "Animated" : false,
                  "IDs": [116, 943, 234, 38793]
                }
            }
            """;
        JsonValue expected = JsonObject.of("Image", JsonObject.of(
            "Width", num("800"),
            "Height", num("600"),
            "Title", str("View from 15th Floor"),
            "Thumbnail", JsonObject.of(
                "Url", str("http://www.example.com/image/481989943"),
                "Height", num("125"),
                "Width", num("100")),
            "Animated", JsonBoolean.FALSE,
            "IDs", JsonArray.of(num("116"), num("943"), num("234"), num("38793"))));
        assertEquals(expected, parse(text));
    }

    @Test
    void parsesTheSecondExampleOfTheRfcKeepingEveryWrittenDigit() {
        String text = """
            [
              {
                 "precision": "zip",
                 "Latitude":  37.7668,
                 "Longitude": -122.3959,
                 "Address":   "",
                 "City":      "SAN FRANCISCO",
                 "State":     "CA",
                 "Zip":       "94107",
                 "Country":   "US"
              },
              {
                 "precision": "zip",
                 "Latitude":  37.371991,
                 "Longitude": -122.026020,
                 "Address":   "",
                 "City":      "SUNNYVALE",
                 "State":     "CA",
                 "Zip":       "94085",
                 "Country":   "US"
              }
            ]
            """;
        JsonArray array = (JsonArray) parse(text);
        assertEquals(2, array.items().size());
        JsonObject second = (JsonObject) array.items().get(1);
        // "-122.026020" has a trailing zero, which is part of what was written and must survive.
        assertEquals(num("-122.026020"), second.members().get("Longitude"));
        assertEquals("-122.026020", ((JsonNumber) second.members().get("Longitude")).value().toPlainString());
        assertEquals(str("SUNNYVALE"), second.members().get("City"));
        assertEquals(List.of("precision", "Latitude", "Longitude", "Address", "City", "State", "Zip", "Country"),
            List.copyOf(second.members().keySet()), "members keep the order they were written in");
    }

    // ------------------------------------------------------------------ strings

    @Test
    void decodesEverySimpleEscape() {
        assertEquals(str("\"\\/\b\f\n\r\t"), parse("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\""));
    }

    @Test
    void decodesUnicodeEscapesInEitherCase() {
        assertEquals(str("\u00e9"), parse("\"\\u00e9\""));
        assertEquals(str("\u00e9"), parse("\"\\u00E9\""));
        assertEquals(str("AB"), parse("\"\\u0041\\u0042\""));
        assertEquals(str("\0"), parse("\"\\u0000\""), "an escaped NUL is allowed; only a raw one is not");
    }

    @Test
    void joinsASurrogatePairWrittenAsTwoEscapes() {
        String grinning = cp(0x1F600);
        assertEquals(str(grinning), parse("\"\\ud83d\\ude00\""));
        assertEquals(str(grinning), parse("\"\\uD83D\\uDE00\""));
        assertEquals(str("a" + grinning + "b"), parse("\"a\\ud83d\\ude00b\""));
        assertEquals(2, ((JsonString) parse("\"\\ud83d\\ude00\"")).value().length(), "two UTF-16 units, one code point");
    }

    @Test
    void keepsRawCharactersOutsideAsciiUntouched() {
        String text = "é日本語" + cp(0x1F600) + cp(0x2028) + "\u007f";
        assertEquals(str(text), parse("\"" + text + "\""));
    }

    // ------------------------------------------------------------------ numbers keep their digits

    @Test
    void zeroPointOneIsOneTenthAndNotTheNearestDouble() {
        BigDecimal parsed = ((JsonNumber) parse("0.1")).value();
        assertEquals(new BigDecimal("0.1"), parsed);
        assertNotEquals(0, parsed.compareTo(new BigDecimal(0.1)), "a double 0.1 is 0.1000000000000000055511151231257827…");
    }

    @Test
    void anIntegerAboveTwoToTheFiftyThirdSurvivesExactly() {
        // 2^53 + 1 = 9007199254740993 is the first integer a double cannot hold.
        assertEquals("9007199254740993", ((JsonNumber) parse("9007199254740993")).value().toPlainString());
        assertNotEquals(9007199254740993L, (long) (double) 9007199254740993L, "premise: a double does lose it");
    }

    @Test
    void aThousandDigitIntegerAndExtremeExponentsAreKept() {
        String thousand = "7".repeat(1000);
        assertEquals(new BigDecimal(thousand), ((JsonNumber) parse(thousand)).value());
        assertEquals(new BigDecimal("1e400"), ((JsonNumber) parse("1e400")).value(), "beyond any double");
        assertEquals(new BigDecimal("1e-400"), ((JsonNumber) parse("1e-400")).value());
    }

    @Test
    void anExponentThatDoesNotFitInAnIntIsReportedAsOutOfRangeNotAsACrash() {
        JsonParseException e = assertThrows(JsonParseException.class, () -> parse("1e99999999999"));
        assertTrue(e.getMessage().contains("out of range"), e.getMessage());
        assertEquals(1, e.column());
    }

    // ------------------------------------------------------------------ objects

    @Test
    void membersKeepTheirOrderButEqualityIgnoresIt() {
        JsonObject ab = (JsonObject) parse("{\"b\":1,\"a\":2}");
        assertEquals(List.of("b", "a"), List.copyOf(ab.members().keySet()));
        assertEquals(parse("{\"a\":2,\"b\":1}"), ab, "the same members in another order are the same object");
        assertNotEquals(parse("[1,2]"), parse("[2,1]"), "arrays, by contrast, are ordered");
    }

    // ------------------------------------------------------------------ what must be rejected, and where

    /** The text, the line and column the error must name, and a fragment of the explanation. */
    record Bad(String text, int line, int column, String message) {
        @Override
        public String toString() {
            // Quoted, so that the empty text and all-whitespace text still make a readable, non-blank test name.
            StringBuilder shown = new StringBuilder("'");
            for (char c : text.toCharArray()) {
                if (c < 0x20 || c > 0x7e) {
                    shown.append("<U+%04X>".formatted((int) c));
                } else {
                    shown.append(c);
                }
            }
            return shown.append('\'').toString();
        }
    }

    static Stream<Bad> rejected() {
        String pairHigh = new String(new char[] {'"', (char) 0xD83D, '"'});
        String pairLow = new String(new char[] {'"', (char) 0xDE00, '"'});
        return Stream.of(
            // structure
            new Bad("", 1, 1, "unexpected end of input, expected a value"),
            new Bad("   ", 1, 4, "unexpected end of input, expected a value"),
            new Bad("[1,]", 1, 4, "unexpected character ']', expected a value"),
            new Bad("[,1]", 1, 2, "unexpected character ',', expected a value"),
            new Bad("[1 2]", 1, 4, "expected ',' or ']'"),
            new Bad("[1", 1, 3, "unexpected end of input, expected ',' or ']'"),
            new Bad("[", 1, 2, "unexpected end of input, expected a value"),
            new Bad("{\"a\":1,}", 1, 8, "expected a member name"),
            new Bad("{\"a\" 1}", 1, 6, "expected ':' after the member name"),
            new Bad("{\"a\"", 1, 5, "unexpected end of input, expected ':'"),
            new Bad("{a:1}", 1, 2, "expected a member name"),
            new Bad("{'a':1}", 1, 2, "expected a member name"),
            new Bad("{\"a\":1 \"b\":2}", 1, 8, "expected ',' or '}'"),
            new Bad("{", 1, 2, "unexpected end of input, expected a member name"),
            new Bad("{\"a\":}", 1, 6, "unexpected character '}', expected a value"),
            new Bad("['a']", 1, 2, "unexpected character ''', expected a value"),
            // trailing content
            new Bad("1 2", 1, 3, "after the end of the value"),
            new Bad("[1] x", 1, 5, "after the end of the value"),
            new Bad("{} {}", 1, 4, "after the end of the value"),
            new Bad("nullx", 1, 5, "after the end of the value"),
            // numbers
            new Bad("[01]", 1, 3, "leading zeros"),
            new Bad("-01", 1, 3, "leading zeros"),
            new Bad("00", 1, 2, "leading zeros"),
            new Bad("+1", 1, 1, "unexpected character '+', expected a value"),
            new Bad(".5", 1, 1, "unexpected character '.', expected a value"),
            new Bad("-", 1, 2, "unexpected end of input, expected a digit"),
            new Bad("--1", 1, 2, "unexpected character '-', expected a digit"),
            new Bad("-.5", 1, 2, "unexpected character '.', expected a digit"),
            new Bad("5.", 1, 3, "expected a digit after the decimal point"),
            new Bad("5.e3", 1, 3, "expected a digit after the decimal point"),
            new Bad("1e", 1, 3, "expected a digit in the exponent"),
            new Bad("1e+", 1, 4, "expected a digit in the exponent"),
            new Bad("1.5.2", 1, 4, "after the end of the value"),
            new Bad("0x10", 1, 2, "after the end of the value"),
            new Bad("1_000", 1, 2, "after the end of the value"),
            new Bad("NaN", 1, 1, "unexpected character 'N', expected a value"),
            new Bad("Infinity", 1, 1, "unexpected character 'I', expected a value"),
            new Bad("-Infinity", 1, 2, "unexpected character 'I', expected a digit"),
            new Bad("\uff11", 1, 1, "unexpected character U+FF11, expected a value"),   // a fullwidth digit is not a JSON digit
            // literals
            new Bad("tru", 1, 1, "expected 'true'"),
            new Bad("True", 1, 1, "unexpected character 'T', expected a value"),
            new Bad("nul", 1, 1, "expected 'null'"),
            new Bad("falsy", 1, 1, "expected 'false'"),
            // strings
            new Bad("\"abc", 1, 5, "unexpected end of input inside a string"),
            new Bad("\"a\nb\"", 1, 3, "unescaped control character U+000A"),
            new Bad("\"a\tb\"", 1, 3, "unescaped control character U+0009"),
            new Bad("\"a" + (char) 0 + "b\"", 1, 3, "unescaped control character U+0000"),
            new Bad("\"a" + (char) 0x1F + "b\"", 1, 3, "unescaped control character U+001F"),
            new Bad("\"" + (char) 0xD83D, 1, 2, "unpaired surrogate U+D83D"),                 // ends right after a high half
            new Bad("\"\\u0041", 1, 8, "unexpected end of input inside a string"),             // an escape that is complete but unclosed
            new Bad("\"\\x\"", 1, 2, "invalid escape sequence \\x"),
            new Bad("\"\\'\"", 1, 2, "invalid escape sequence \\'"),
            new Bad("\"\\", 1, 3, "unexpected end of input after a backslash"),
            new Bad("\"\\u12\"", 1, 2, "four hexadecimal digits"),
            new Bad("\"\\uZZZZ\"", 1, 4, "expected a hexadecimal digit"),
            new Bad("\"\\u00g1\"", 1, 6, "expected a hexadecimal digit"),
            new Bad("\"\\u\uff10\uff10\uff14\uff11\"", 1, 4, "expected a hexadecimal digit"), // fullwidth digits: Character.digit would accept them
            new Bad("\"\\ud83d\"", 1, 2, "unpaired surrogate escape"),
            new Bad("\"\\ude00\"", 1, 2, "unpaired surrogate escape"),
            new Bad("\"\\ud83d\\u0041\"", 1, 2, "unpaired surrogate escape"),
            new Bad("\"\\ud83dx\"", 1, 2, "unpaired surrogate escape"),
            new Bad("\"\\ud83d\\ud83d\"", 1, 2, "unpaired surrogate escape"),
            new Bad(pairHigh, 1, 2, "unpaired surrogate U+D83D"),
            new Bad(pairLow, 1, 2, "unpaired surrogate U+DE00"),
            // whitespace and comments that are not JSON
            new Bad("\f1", 1, 1, "unexpected character U+000C, expected a value"),
            new Bad(String.valueOf((char) 0x0B) + "1", 1, 1, "unexpected character U+000B, expected a value"),
            new Bad(String.valueOf((char) 0xA0) + "1", 1, 1, "unexpected character U+00A0, expected a value"),
            new Bad(String.valueOf((char) 0xFEFF) + "1", 1, 1, "unexpected character U+FEFF, expected a value"),
            new Bad("[1 /* c */]", 1, 4, "expected ',' or ']'"),
            new Bad("// c\n1", 1, 1, "unexpected character '/', expected a value"),
            new Bad("[1]]", 1, 4, "after the end of the value"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void rejectsInvalidTextAndSaysExactlyWhere(Bad bad) {
        JsonParseException e = assertThrows(JsonParseException.class, () -> parse(bad.text()), "accepted: " + bad);
        assertEquals(bad.line() + ":" + bad.column(), e.line() + ":" + e.column(), "position of the error for " + bad + ": " + e.getMessage());
        assertTrue(e.getMessage().contains(bad.message()), "message for " + bad + " was: " + e.getMessage());
        assertTrue(e.getMessage().startsWith("line " + bad.line() + ", column " + bad.column() + ": "), e.getMessage());
    }

    @Test
    void duplicateMemberNamesAreRefusedAtTheSecondOccurrence() {
        JsonParseException e = assertThrows(JsonParseException.class, () -> parse("{\"a\":1,\"a\":2}"));
        assertEquals(8, e.column(), "the position of the second \"a\"");
        assertTrue(e.getMessage().contains("duplicate member name \"a\""), e.getMessage());
        // Different documents, same names in different objects: that is fine.
        parse("{\"a\":{\"a\":1},\"b\":{\"a\":2}}");
    }

    // ------------------------------------------------------------------ positions

    @Test
    void positionsAreCountedAcrossLines() {
        JsonParseException e = assertThrows(JsonParseException.class, () -> parse("{\n  \"a\": 1,\n  \"b\": ]\n}"));
        assertEquals(3, e.line());
        assertEquals(8, e.column(), "two spaces, then \"b\": and a space, put the bracket at index 7 of its line");
        assertEquals("{\n  \"a\": 1,\n  \"b\": ".length(), e.offset());
    }

    @Test
    void aCarriageReturnLineFeedPairCountsAsOneLine() {
        JsonParseException e = assertThrows(JsonParseException.class, () -> parse("{\r\n\"a\": ]\r\n}"));
        assertEquals(2, e.line());
        assertEquals(6, e.column());
    }

    @Test
    void columnsCountUtf16UnitsSoACharacterOutsideTheBmpTakesTwo() {
        // ["<emoji>", ]  ->  indexes: [0 "1 emoji 2-3 "4 ,5 space 6 ]7
        JsonParseException e = assertThrows(JsonParseException.class, () -> parse("[\"" + cp(0x1F600) + "\", ]"));
        assertEquals(1, e.line());
        assertEquals(8, e.column());
    }

    // ------------------------------------------------------------------ nesting depth

    @Test
    void nestingUpToTheLimitIsAcceptedAndOneMoreIsRefused() {
        int limit = JsonParser.DEFAULT_MAX_DEPTH;
        assertEquals(limit, depthOf(parse("[".repeat(limit) + "]".repeat(limit))));

        JsonParseException e = assertThrows(JsonParseException.class,
            () -> parse("[".repeat(limit + 1) + "]".repeat(limit + 1)));
        assertTrue(e.getMessage().contains("deeper than the limit of " + limit), e.getMessage());
        assertEquals(limit + 1, e.column(), "the error is at the bracket that crosses the limit");
    }

    @Test
    void aHostileDocumentOfAMillionBracketsIsRefusedWithoutOverflowingTheStack() {
        String hostile = "[".repeat(1_000_000);
        JsonParseException e = assertThrows(JsonParseException.class, () -> parse(hostile));
        assertTrue(e.getMessage().contains("deeper than the limit"), e.getMessage());
        assertThrows(JsonParseException.class, () -> parse("{\"a\":".repeat(100_000)));
    }

    @Test
    void theLimitIsConfigurableAndCountsArraysAndObjectsTogether() {
        assertEquals(2, depthOf(JsonParser.parse("[[1]]", 2)));
        assertThrows(JsonParseException.class, () -> JsonParser.parse("[[1]]", 1));
        JsonParser.parse("{\"a\":[1]}", 2);
        assertThrows(JsonParseException.class, () -> JsonParser.parse("{\"a\":[1]}", 1));
        JsonParser.parse("1", 1); // a scalar needs no depth at all
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void aLimitBelowOneIsAProgrammingError(int limit) {
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parse("[]", limit));
    }

    @Test
    void nullInputIsAProgrammingError() {
        assertThrows(NullPointerException.class, () -> JsonParser.parse(null));
    }

    private static int depthOf(JsonValue value) {
        return switch (value) {
            case JsonArray array -> 1 + array.items().stream().mapToInt(JsonParserTest::depthOf).max().orElse(0);
            case JsonObject object -> 1 + object.members().values().stream().mapToInt(JsonParserTest::depthOf).max().orElse(0);
            case JsonNull scalar -> 0;
            case JsonBoolean scalar -> 0;
            case JsonNumber scalar -> 0;
            case JsonString scalar -> 0;
        };
    }
}
