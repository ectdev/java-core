package io.github.ectdev.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Randomised properties. Every seed is fixed and appears in the failure message, so a failure can be replayed.
 *
 * <ul>
 *   <li>For any value, parsing what the writer wrote gives the value back, compact or indented.
 *   <li>Writing is a function of the value alone: write, parse, write again gives the same text.
 *   <li>For any damaged text the parser either returns a value or throws {@link JsonParseException} with a position
 *       inside the text. Anything else (a stack overflow, an index error) fails the test.
 * </ul>
 *
 * The strings are drawn from a pool made of exactly the characters that break naive code: the quote, the backslash,
 * every kind of control character, DEL, the line and paragraph separators, a byte order mark, characters from outside
 * the Basic Multilingual Plane, and the member separators themselves.
 */
class JsonRoundTripTest {

    private static final long[] SEEDS = {1, 2, 3, 7, 42, 2024, 31337, 8675309};

    private static final String[] PIECES = {
        "a", "b", "Z", "0", "9", " ", "_", "-", "/", "~", "é", "日", "ß",
        "\"", "\\", "\b", "\f", "\n", "\r", "\t", "\0", "\u001f", "\u007f", "\u0080",
        " ", " ", "﻿", "￿",
        new String(Character.toChars(0x1F600)), new String(Character.toChars(0x10000)), new String(Character.toChars(0x10FFFF)),
        "{", "}", "[", "]", ",", ":", "null", "true"
    };

    private static String randomString(Random random) {
        StringBuilder out = new StringBuilder();
        int pieces = random.nextInt(8);
        for (int i = 0; i < pieces; i++) {
            out.append(PIECES[random.nextInt(PIECES.length)]);
        }
        return out.toString();
    }

    private static JsonNumber randomNumber(Random random) {
        BigInteger unscaled = new BigInteger(random.nextInt(90), random);
        if (random.nextBoolean()) {
            unscaled = unscaled.negate();
        }
        int scale = random.nextInt(61) - 30;
        return new JsonNumber(new BigDecimal(unscaled, scale));
    }

    private static JsonValue randomValue(Random random, int depth) {
        int kinds = depth <= 0 ? 4 : 6;
        return switch (random.nextInt(kinds)) {
            case 0 -> JsonNull.INSTANCE;
            case 1 -> JsonBoolean.of(random.nextBoolean());
            case 2 -> randomNumber(random);
            case 3 -> new JsonString(randomString(random));
            case 4 -> {
                List<JsonValue> items = new ArrayList<>();
                for (int i = random.nextInt(5); i > 0; i--) {
                    items.add(randomValue(random, depth - 1));
                }
                yield new JsonArray(items);
            }
            default -> {
                Map<String, JsonValue> members = new LinkedHashMap<>();
                for (int i = random.nextInt(5); i > 0; i--) {
                    members.put(randomString(random), randomValue(random, depth - 1));
                }
                yield new JsonObject(members);
            }
        };
    }

    @Test
    void whatTheWriterWritesTheParserReadsBackAsTheSameValue() {
        int checked = 0;
        for (long seed : SEEDS) {
            Random random = new Random(seed);
            for (int i = 0; i < 400; i++) {
                JsonValue value = randomValue(random, 4);
                String where = "seed " + seed + ", value " + i;
                for (int indent : new int[] {0, 1, 2, 4}) {
                    String text = JsonWriter.write(value, indent);
                    assertEquals(value, JsonParser.parse(text), where + ", indent " + indent + ", text: " + text);
                }
                checked++;
            }
        }
        assertEquals(SEEDS.length * 400, checked);
    }

    @Test
    void writingTwiceGivesTheSameTextAndIndentationOnlyAddsWhitespace() {
        for (long seed : SEEDS) {
            Random random = new Random(seed);
            for (int i = 0; i < 300; i++) {
                JsonValue value = randomValue(random, 4);
                String compact = JsonWriter.write(value);
                String again = JsonWriter.write(JsonParser.parse(compact));
                assertEquals(compact, again, "seed " + seed + ", value " + i);
                // Indented text, parsed and written compact, is the compact text: indentation changes nothing else.
                assertEquals(compact, JsonWriter.write(JsonParser.parse(JsonWriter.write(value, 3))), "seed " + seed + ", value " + i);
            }
        }
    }

    @Test
    void anObjectWithTheSameMembersInAnotherOrderIsEqualButWritesDifferently() {
        JsonValue ab = JsonParser.parse("{\"a\":1,\"b\":2}");
        JsonValue ba = JsonParser.parse("{\"b\":2,\"a\":1}");
        assertEquals(ab, ba);
        assertEquals(ab.hashCode(), ba.hashCode());
        assertEquals("{\"a\":1,\"b\":2}", ab.toJson());
        assertEquals("{\"b\":2,\"a\":1}", ba.toJson());
    }

    @Test
    void damagedTextIsEitherParsedOrRefusedWithAPositionInsideTheText() {
        String alphabet = "{}[],:\"\\ -+.eE0123456789tfnul\n\t\r\u0001é";
        int parsed = 0;
        int refused = 0;
        for (long seed : SEEDS) {
            Random random = new Random(seed * 31 + 5);
            for (int i = 0; i < 600; i++) {
                String original = JsonWriter.write(randomValue(random, 3), random.nextBoolean() ? 0 : 2);
                String damaged = damage(original, random, alphabet);
                String where = "seed " + seed + ", case " + i + ", text: " + damaged;
                try {
                    JsonValue value = JsonParser.parse(damaged);
                    // Whatever got through must itself be stable.
                    assertEquals(value, JsonParser.parse(JsonWriter.write(value)), where);
                    parsed++;
                } catch (JsonParseException e) {
                    assertTrue(e.offset() >= 0 && e.offset() <= damaged.length(), where + ", offset " + e.offset());
                    assertEquals(1 + count(damaged, '\n', e.offset()), e.line(), where);
                    int lineStart = damaged.lastIndexOf('\n', e.offset() - 1) + 1;
                    assertEquals(e.offset() - lineStart + 1, e.column(), where);
                    assertTrue(e.getMessage().startsWith("line " + e.line() + ", column " + e.column() + ": "), where);
                    refused++;
                }
            }
        }
        // The test would be vacuous if the damage were always harmless or always fatal.
        assertTrue(parsed > 100, "damage that still parses: " + parsed);
        assertTrue(refused > 1000, "damage that is refused: " + refused);
    }

    private static String damage(String text, Random random, String alphabet) {
        if (text.isEmpty()) {
            return String.valueOf(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        int at = random.nextInt(text.length());
        char c = alphabet.charAt(random.nextInt(alphabet.length()));
        return switch (random.nextInt(4)) {
            case 0 -> text.substring(0, at) + text.substring(at + 1);                  // delete one character
            case 1 -> text.substring(0, at) + c + text.substring(at);                  // insert one
            case 2 -> text.substring(0, at) + c + text.substring(at + 1);              // replace one
            default -> text.substring(0, at);                                           // cut off
        };
    }

    private static int count(String text, char c, int before) {
        int n = 0;
        for (int i = 0; i < before; i++) {
            if (text.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    @Test
    void everyPrefixOfAValidDocumentIsRefusedOrIsItselfAValidDocument() {
        String text = JsonWriter.write(JsonParser.parse("{\"a\":[1,2.5,{\"b\":null}],\"c\":\"x\\ny\",\"d\":[true,false]}"));
        int complete = 0;
        for (int end = 0; end <= text.length(); end++) {
            String prefix = text.substring(0, end);
            try {
                JsonParser.parse(prefix);
                complete++;
            } catch (JsonParseException e) {
                assertTrue(e.offset() <= prefix.length(), "prefix of length " + end + ": " + e.getMessage());
            }
        }
        // The whole text is one; so is no shorter prefix, because every one of them ends inside a container.
        assertEquals(1, complete);
    }
}
