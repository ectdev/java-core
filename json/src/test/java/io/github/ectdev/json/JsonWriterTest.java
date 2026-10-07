package io.github.ectdev.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The expected texts are written out by hand, character by character. Test sources never contain the
 * backslash-u sequence (Java would read it as an escape even in a comment); control characters are built from
 * numbers and expected escapes from doubled backslashes.
 */
class JsonWriterTest {

    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    @Test
    void scalarsAreWrittenAsTheirLiterals() {
        assertEquals("null", JsonWriter.write(JsonNull.INSTANCE));
        assertEquals("true", JsonWriter.write(JsonBoolean.TRUE));
        assertEquals("false", JsonWriter.write(JsonBoolean.FALSE));
        assertEquals("42", JsonWriter.write(JsonNumber.of(42)));
        assertEquals("-17", JsonWriter.write(JsonNumber.of(-17)));
        assertEquals("3.14", JsonWriter.write(JsonNumber.of("3.14")));
        assertEquals("\"abc\"", JsonWriter.write(new JsonString("abc")));
    }

    @Test
    void numbersKeepTheirScaleSoATrailingZeroIsNotLost() {
        assertEquals("-122.026020", JsonWriter.write(JsonNumber.of("-122.026020")));
        assertEquals("1.50", JsonWriter.write(JsonNumber.of("1.50")));
        assertEquals("0.0", JsonWriter.write(JsonNumber.of("0.0")));
    }

    @Test
    void exponentNotationIsValidJsonAndReadsBackAsTheSameNumber() {
        // BigDecimal.toString switches to exponent form when the exponent would be positive.
        BigDecimal thousand = new BigDecimal("1E+3");
        String text = JsonWriter.write(new JsonNumber(thousand));
        assertEquals("1E+3", text);
        assertEquals(new JsonNumber(thousand), JsonParser.parse(text));
        assertEquals("1E-10", JsonWriter.write(JsonNumber.of("1E-10")));
    }

    @Test
    void compactTextHasNoWhitespaceOutsideStrings() {
        JsonValue document = JsonObject.of(
            "a", JsonArray.of(JsonNumber.of(1), JsonNumber.of(2), new JsonArray(List.of())),
            "b", JsonObject.of("c", JsonNull.INSTANCE, "d", new JsonObject(java.util.Map.of())),
            "e with space", new JsonString("x y"));
        assertEquals("{\"a\":[1,2,[]],\"b\":{\"c\":null,\"d\":{}},\"e with space\":\"x y\"}", JsonWriter.write(document));
        assertEquals(JsonWriter.write(document), document.toJson());
        assertEquals(JsonWriter.write(document), JsonWriter.write(document, 0));
    }

    @Test
    void indentedTextIsWrittenLikeThis() {
        JsonValue document = JsonObject.of(
            "a", JsonArray.of(JsonNumber.of(1), new JsonArray(List.of())),
            "b", JsonObject.of("c", JsonNull.INSTANCE),
            "d", new JsonObject(java.util.Map.of()));
        String expected = """
            {
              "a": [
                1,
                []
              ],
              "b": {
                "c": null
              },
              "d": {}
            }""";
        assertEquals(expected, JsonWriter.write(document, 2));
        assertEquals(expected, document.toPrettyJson(2));
    }

    @Test
    void theIndentIsTheNumberOfSpacesPerLevel() {
        JsonValue document = JsonArray.of(JsonArray.of(JsonNumber.of(1)));
        assertEquals("[\n    [\n        1\n    ]\n]", JsonWriter.write(document, 4));
        assertEquals("[\n [\n  1\n ]\n]", JsonWriter.write(document, 1));
    }

    @Test
    void aScalarIsNotIndentedAtAll() {
        assertEquals("1", JsonWriter.write(JsonNumber.of(1), 4));
        assertEquals("\"x\"", JsonWriter.write(new JsonString("x"), 4));
        assertEquals("[]", JsonWriter.write(new JsonArray(List.of()), 4));
    }

    @Test
    void stringsEscapeExactlyWhatTheGrammarRequires() {
        assertEquals("\"\\\"\"", JsonWriter.write(new JsonString("\"")));
        assertEquals("\"\\\\\"", JsonWriter.write(new JsonString("\\")));
        assertEquals("\"\\b\\f\\n\\r\\t\"", JsonWriter.write(new JsonString("\b\f\n\r\t")));
        // Forward slash may be escaped but need not be; this writer leaves it alone.
        assertEquals("\"a/b\"", JsonWriter.write(new JsonString("a/b")));
    }

    @Test
    void everyOtherControlCharacterBecomesABackslashUWithFourLowercaseHexDigits() {
        for (int c = 0; c < 0x20; c++) {
            String written = JsonWriter.write(new JsonString(String.valueOf((char) c)));
            String short_ = switch (c) {
                case '\b' -> "\\b";
                case '\f' -> "\\f";
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                default -> "\\u%04x".formatted(c);
            };
            assertEquals("\"" + short_ + "\"", written, "control character " + c);
        }
        assertEquals("\"\\u0000\"", JsonWriter.write(new JsonString("\0")));
        assertEquals("\"\\u001f\"", JsonWriter.write(new JsonString("\u001f")));
        assertEquals("\"\\u000b\"", JsonWriter.write(new JsonString(String.valueOf((char) 0x0B))));
    }

    @Test
    void charactersAboveTheControlRangeAreWrittenAsTheyAre() {
        for (String text : List.of(" ", "~", "\u007f", "é", "日本語", cp(0x1F600), cp(0x2028), cp(0x2029), cp(0xFEFF), cp(0x10FFFF))) {
            assertEquals("\"" + text + "\"", JsonWriter.write(new JsonString(text)), "U+" + Integer.toHexString(text.codePointAt(0)));
        }
    }

    @Test
    void memberNamesAreEscapedLikeStrings() {
        assertEquals("{\"a\\\"b\":1,\"c\\nd\":2,\"\":3}",
            JsonWriter.write(JsonObject.of("a\"b", JsonNumber.of(1), "c\nd", JsonNumber.of(2), "", JsonNumber.of(3))));
    }

    @Test
    void membersAreWrittenInTheOrderTheyWereGiven() {
        assertEquals("{\"z\":1,\"a\":2,\"m\":3}",
            JsonWriter.write(JsonObject.of("z", JsonNumber.of(1), "a", JsonNumber.of(2), "m", JsonNumber.of(3))));
    }

    @Test
    void writingDoesNotRecurseOnTheNativeStackForTheDepthTheParserAllows() {
        // The parser accepts 512 levels, so the writer must cope with at least that.
        int depth = JsonParser.DEFAULT_MAX_DEPTH;
        JsonValue deep = JsonParser.parse("[".repeat(depth) + "]".repeat(depth));
        String compact = JsonWriter.write(deep);
        assertEquals("[".repeat(depth) + "]".repeat(depth), compact);
        JsonWriter.write(deep, 2);
    }

    @Test
    void badArgumentsAreProgrammingErrors() {
        assertThrows(NullPointerException.class, () -> JsonWriter.write(null));
        assertThrows(NullPointerException.class, () -> JsonWriter.write(null, 2));
        assertThrows(IllegalArgumentException.class, () -> JsonWriter.write(JsonNull.INSTANCE, -1));
    }
}
