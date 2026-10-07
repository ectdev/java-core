package io.github.ectdev.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Section 5 of RFC 6901 gives a document and the value each pointer must yield; this is that table, verbatim. */
class JsonPointerTest {

    /** The document of RFC 6901 section 5. */
    private static final String RFC_DOCUMENT = """
        {
           "foo": ["bar", "baz"],
           "": 0,
           "a/b": 1,
           "c%d": 2,
           "e^f": 3,
           "g|h": 4,
           "i\\\\j": 5,
           "k\\"l": 6,
           " ": 7,
           "m~n": 8
        }
        """;

    private static final JsonValue DOCUMENT = JsonParser.parse(RFC_DOCUMENT);

    static Stream<Arguments> rfcTable() {
        return Stream.of(
            Arguments.of("", DOCUMENT),
            Arguments.of("/foo", JsonParser.parse("[\"bar\", \"baz\"]")),
            Arguments.of("/foo/0", new JsonString("bar")),
            Arguments.of("/foo/1", new JsonString("baz")),
            Arguments.of("/", JsonNumber.of(0)),
            Arguments.of("/a~1b", JsonNumber.of(1)),
            Arguments.of("/c%d", JsonNumber.of(2)),
            Arguments.of("/e^f", JsonNumber.of(3)),
            Arguments.of("/g|h", JsonNumber.of(4)),
            Arguments.of("/i\\j", JsonNumber.of(5)),
            Arguments.of("/k\"l", JsonNumber.of(6)),
            Arguments.of("/ ", JsonNumber.of(7)),
            Arguments.of("/m~0n", JsonNumber.of(8)));
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("rfcTable")
    void everyRowOfTheRfcTable(String pointer, JsonValue expected) {
        assertEquals(Optional.of(expected), DOCUMENT.at(pointer));
    }

    @Test
    void theDocumentIsWhatTheRfcPrintsSoTheTableMeansWhatItSays() {
        // Guards the text block above: the escapes there are easy to get subtly wrong.
        JsonObject object = (JsonObject) DOCUMENT;
        assertEquals(10, object.members().size());
        assertTrue(object.members().containsKey("i\\j"));
        assertTrue(object.members().containsKey("k\"l"));
        assertTrue(object.members().containsKey(""));
        assertTrue(object.members().containsKey(" "));
    }

    @Test
    void tildeZeroOneIsTheTwoCharactersTildeOneAndNotASlash() {
        // The reason the escapes must be decoded in one pass: "~01" is "~" then "1".
        JsonValue document = JsonParser.parse("{\"~1\":\"tilde-one\",\"/\":\"slash\",\"~\":\"tilde\"}");
        assertEquals(Optional.of(new JsonString("tilde-one")), document.at("/~01"));
        assertEquals(Optional.of(new JsonString("slash")), document.at("/~1"));
        assertEquals(Optional.of(new JsonString("tilde")), document.at("/~0"));
    }

    @Test
    void tildeTildeIsNotAnEscapeOfAnEscape() {
        JsonValue document = JsonParser.parse("{\"~~\":1,\"~/\":2}");
        assertEquals(Optional.of(JsonNumber.of(1)), document.at("/~0~0"));
        assertEquals(Optional.of(JsonNumber.of(2)), document.at("/~0~1"));
    }

    @Test
    void anEmptyTokenIsTheMemberWithTheEmptyName() {
        JsonValue document = JsonParser.parse("{\"\":{\"\":\"deep\"},\"a\":1}");
        assertEquals(Optional.of(JsonParser.parse("{\"\":\"deep\"}")), document.at("/"));
        assertEquals(Optional.of(new JsonString("deep")), document.at("//"));
        assertEquals(Optional.empty(), document.at("///"), "a third empty token leads into a string");
        assertEquals(Optional.empty(), document.at("/a/"), "a number has no member of any name");
    }

    // ------------------------------------------------------------------ array indexes

    private static final JsonValue ARRAY = JsonParser.parse("[10,11,12,13,14,15,16,17,18,19,20]");

    @Test
    void indexesAreZeroBasedDecimalWithoutLeadingZeros() {
        assertEquals(Optional.of(JsonNumber.of(10)), ARRAY.at("/0"));
        assertEquals(Optional.of(JsonNumber.of(19)), ARRAY.at("/9"));
        assertEquals(Optional.of(JsonNumber.of(20)), ARRAY.at("/10"), "two digits");
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"/11", "/99", "/00", "/01", "/010", "/-1", "/-", "/+1", "/1.0", "/1e0", "/ 1", "/1 ", "/a", "/",
        "/0x1", "/1-", "/1+", "/1.", "/1,", "/2147483648", "/4294967296", "/9999999999", "/99999999999999999999", "/٣" /* Arabic-Indic digit three */})
    void anythingThatIsNotAValidIndexLeadsNowhere(String pointer) {
        assertEquals(Optional.empty(), ARRAY.at(pointer));
    }

    @Test
    void aNonDigitBelowTheDigitRangeIsNotArithmetic() {
        // A scan that computes 10 * value + (c - '0') without checking c turns "1-" into 10 - 3 = 7, which is an
        // existing element. The tokens above that do not land in range would not show it.
        assertEquals(Optional.of(JsonNumber.of(17)), ARRAY.at("/7"));
        assertEquals(Optional.empty(), ARRAY.at("/1-"));
        assertEquals(Optional.of(JsonNumber.of(15)), ARRAY.at("/5"));
        assertEquals(Optional.empty(), ARRAY.at("/1+"));
    }

    @Test
    void theLargestNineDigitIndexIsStillJustOutOfRangeNotAnError() {
        assertEquals(Optional.empty(), ARRAY.at("/999999999"));
        assertEquals(Optional.empty(), ARRAY.at("/100000000"));
    }

    @Test
    void anIndexIsNotAMemberNameOfAnObject() {
        JsonValue document = JsonParser.parse("{\"0\":\"zero\"}");
        assertEquals(Optional.of(new JsonString("zero")), document.at("/0"), "on an object the token is a name, digits or not");
        assertEquals(Optional.empty(), document.at("/00"));
    }

    // ------------------------------------------------------------------ leading to nowhere

    @Test
    void aMissingMemberAndAStepIntoAScalarLeadNowhere() {
        assertEquals(Optional.empty(), DOCUMENT.at("/nope"));
        assertEquals(Optional.empty(), DOCUMENT.at("/foo/2"));
        assertEquals(Optional.empty(), DOCUMENT.at("/foo/0/x"), "into a string");
        assertEquals(Optional.empty(), DOCUMENT.at("/a~1b/x"), "into a number");
        assertEquals(Optional.empty(), JsonNull.INSTANCE.at("/x"));
        assertEquals(Optional.empty(), JsonBoolean.TRUE.at("/x"));
        assertEquals(Optional.empty(), new JsonString("x").at("/0"));
    }

    @Test
    void thePointerIsCaseSensitive() {
        assertEquals(Optional.empty(), DOCUMENT.at("/FOO"));
    }

    @Test
    void aValueThatIsExplicitlyNullIsFoundAndIsNotTheSameAsMissing() {
        JsonValue document = JsonParser.parse("{\"a\":null}");
        assertEquals(Optional.of(JsonNull.INSTANCE), document.at("/a"));
        assertEquals(Optional.empty(), document.at("/b"));
    }

    @Test
    void aScalarDocumentIsReachedByTheEmptyPointer() {
        assertEquals(Optional.of(JsonNumber.of(7)), JsonNumber.of(7).at(""));
    }

    // ------------------------------------------------------------------ malformed pointers are errors

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"foo", "a/b", " /foo", "#/foo", "0"})
    void aPointerThatDoesNotStartWithASlashIsMalformed(String pointer) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> DOCUMENT.at(pointer));
        assertTrue(e.getMessage().contains("starts with '/'"), e.getMessage());
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"/~", "/~2", "/a~", "/~x", "/a~b", "/~ 0", "/foo/~", "/~/", "/~~"})
    void aTildeNotFollowedByZeroOrOneIsMalformed(String pointer) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> DOCUMENT.at(pointer));
        assertTrue(e.getMessage().contains("'~' must be followed by 0 or 1"), e.getMessage());
    }

    @Test
    void aMalformedTokenIsReportedEvenWhenAnEarlierStepAlreadyLeadsNowhere() {
        // A pointer is checked as a whole, not lazily: "/nope/~" must not be quietly "empty".
        assertThrows(IllegalArgumentException.class, () -> DOCUMENT.at("/nope/~"));
    }

    @Test
    void nullArgumentsAreProgrammingErrors() {
        assertThrows(NullPointerException.class, () -> DOCUMENT.at(null));
        assertThrows(NullPointerException.class, () -> JsonPointer.evaluate(null, "/a"));
    }
}
