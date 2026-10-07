package io.github.ectdev.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The six kinds of value: construction rules, immutability and equality. */
class JsonValuesTest {

    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    // ------------------------------------------------------------------ null and booleans

    @Test
    void everyNullIsEqualToEveryOther() {
        assertEquals(JsonNull.INSTANCE, new JsonNull());
        assertEquals(JsonNull.INSTANCE.hashCode(), new JsonNull().hashCode());
    }

    @Test
    void booleansHaveTwoSharedInstances() {
        assertSame(JsonBoolean.TRUE, JsonBoolean.of(true));
        assertSame(JsonBoolean.FALSE, JsonBoolean.of(false));
        assertEquals(JsonBoolean.TRUE, new JsonBoolean(true));
        assertNotEquals(JsonBoolean.TRUE, JsonBoolean.FALSE);
    }

    // ------------------------------------------------------------------ numbers

    @Test
    void numbersCompareByValueAndScaleLikeBigDecimal() {
        assertEquals(JsonNumber.of(5), JsonNumber.of("5"));
        assertEquals(JsonNumber.of("1.50"), JsonNumber.of("1.50"));
        // Documented: 1.0 and 1.00 record different amounts of written precision.
        assertNotEquals(JsonNumber.of("1.0"), JsonNumber.of("1.00"));
        assertEquals(0, JsonNumber.of("1.0").value().compareTo(JsonNumber.of("1.00").value()));
    }

    @Test
    void numberFactoriesKeepEveryDigit() {
        assertEquals(new BigDecimal("9223372036854775807"), JsonNumber.of(Long.MAX_VALUE).value());
        assertEquals(new BigDecimal("-9223372036854775808"), JsonNumber.of(Long.MIN_VALUE).value());
        assertEquals(new BigDecimal("123456789012345678901234567890.123456789"),
            JsonNumber.of("123456789012345678901234567890.123456789").value());
    }

    @Test
    void aNumberNeedsAValue() {
        assertThrows(NullPointerException.class, () -> new JsonNumber(null));
        assertThrows(NumberFormatException.class, () -> JsonNumber.of("abc"));
    }

    // ------------------------------------------------------------------ strings

    @Test
    void wellFormedUnicodeIsAccepted() {
        new JsonString("");
        new JsonString("plain");
        new JsonString(cp(0x1F600));
        new JsonString("a" + cp(0x1F600) + cp(0x10000) + "b" + cp(0x10FFFF));
        new JsonString("é\u0000￿￾");
    }

    @Test
    void anUnpairedSurrogateIsRefusedWhereverItSits() {
        char high = 0xD83D;
        char low = 0xDE00;
        for (String bad : List.of(
            String.valueOf(high),
            String.valueOf(low),
            "a" + high,
            high + "a",
            low + "" + high,                       // the halves the wrong way round
            "" + high + high + low,                // the first high has no partner
            "" + high + low + low,                 // the last low has no partner
            "x" + low + "y")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new JsonString(bad));
            assertTrue(e.getMessage().contains("unpaired surrogate"), e.getMessage());
        }
    }

    @Test
    void theMessageNamesTheSurrogateAndItsIndex() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new JsonString("ab" + (char) 0xD83D));
        assertEquals("unpaired surrogate U+D83D at index 2: not well-formed Unicode", e.getMessage());
        IllegalArgumentException low = assertThrows(IllegalArgumentException.class, () -> new JsonString("a" + (char) 0xDE00 + "b"));
        assertEquals("unpaired surrogate U+DE00 at index 1: not well-formed Unicode", low.getMessage());
    }

    @Test
    void aStringNeedsAValue() {
        assertThrows(NullPointerException.class, () -> new JsonString(null));
    }

    // ------------------------------------------------------------------ arrays

    @Test
    void anArrayCopiesItsListSoLaterChangesDoNotReachIt() {
        List<JsonValue> source = new ArrayList<>(List.of(JsonNumber.of(1)));
        JsonArray array = new JsonArray(source);
        source.add(JsonNumber.of(2));
        assertEquals(List.of(JsonNumber.of(1)), array.items());
    }

    @Test
    void anArrayCannotBeChangedThroughItsAccessor() {
        JsonArray array = JsonArray.of(JsonNumber.of(1));
        assertThrows(UnsupportedOperationException.class, () -> array.items().add(JsonNull.INSTANCE));
        assertThrows(UnsupportedOperationException.class, () -> array.items().clear());
    }

    @Test
    void anArrayRefusesNullElementsAndANullList() {
        List<JsonValue> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new JsonArray(withNull));
        assertThrows(NullPointerException.class, () -> new JsonArray(null));
    }

    @Test
    void arraysCompareElementByElementInOrder() {
        assertEquals(JsonArray.of(JsonNumber.of(1), JsonNumber.of(2)), JsonArray.of(JsonNumber.of(1), JsonNumber.of(2)));
        assertNotEquals(JsonArray.of(JsonNumber.of(1), JsonNumber.of(2)), JsonArray.of(JsonNumber.of(2), JsonNumber.of(1)));
        assertNotEquals(JsonArray.of(JsonNumber.of(1)), JsonArray.of(JsonNumber.of(1), JsonNumber.of(1)));
    }

    // ------------------------------------------------------------------ objects

    @Test
    void anObjectCopiesItsMapSoLaterChangesDoNotReachIt() {
        Map<String, JsonValue> source = new LinkedHashMap<>();
        source.put("a", JsonNumber.of(1));
        JsonObject object = new JsonObject(source);
        source.put("b", JsonNumber.of(2));
        source.remove("a");
        assertEquals(Map.of("a", JsonNumber.of(1)), object.members());
    }

    @Test
    void anObjectCannotBeChangedThroughItsAccessor() {
        JsonObject object = JsonObject.of("a", JsonNumber.of(1));
        assertThrows(UnsupportedOperationException.class, () -> object.members().put("b", JsonNull.INSTANCE));
        assertThrows(UnsupportedOperationException.class, () -> object.members().remove("a"));
    }

    @Test
    void anObjectKeepsTheInsertionOrderOfTheMapItWasGiven() {
        Map<String, JsonValue> source = new LinkedHashMap<>();
        for (String name : List.of("z", "y", "x", "a", "b", "c", "m")) {
            source.put(name, JsonNull.INSTANCE);
        }
        assertEquals(List.of("z", "y", "x", "a", "b", "c", "m"), List.copyOf(new JsonObject(source).members().keySet()));
    }

    @Test
    void anObjectRefusesNullNamesAndNullValues() {
        Map<String, JsonValue> nullValue = new LinkedHashMap<>();
        nullValue.put("a", null);
        assertThrows(NullPointerException.class, () -> new JsonObject(nullValue));
        Map<String, JsonValue> nullName = new LinkedHashMap<>();
        nullName.put(null, JsonNull.INSTANCE);
        assertThrows(NullPointerException.class, () -> new JsonObject(nullName));
        assertThrows(NullPointerException.class, () -> new JsonObject(null));
    }

    @Test
    void ofBuildsFromAlternatingNamesAndValues() {
        JsonObject object = JsonObject.of("a", JsonNumber.of(1), "b", JsonBoolean.TRUE);
        assertEquals(List.of("a", "b"), List.copyOf(object.members().keySet()));
        assertEquals(JsonNumber.of(1), object.members().get("a"));
        assertEquals(new JsonObject(Map.of()), JsonObject.of());
    }

    @Test
    void ofRefusesAnOddNumberOfArgumentsAndDuplicateNames() {
        IllegalArgumentException odd = assertThrows(IllegalArgumentException.class, () -> JsonObject.of("a"));
        assertEquals("expected alternating names and values, got 1 arguments", odd.getMessage());
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
            () -> JsonObject.of("a", JsonNumber.of(1), "a", JsonNumber.of(2)));
        assertEquals("duplicate member name: a", duplicate.getMessage());
    }

    @Test
    void equalityOfObjectsIgnoresOrderButNotContent() {
        assertEquals(JsonObject.of("a", JsonNumber.of(1), "b", JsonNumber.of(2)), JsonObject.of("b", JsonNumber.of(2), "a", JsonNumber.of(1)));
        assertNotEquals(JsonObject.of("a", JsonNumber.of(1)), JsonObject.of("a", JsonNumber.of(2)));
        assertNotEquals(JsonObject.of("a", JsonNumber.of(1)), JsonObject.of("b", JsonNumber.of(1)));
        assertNotEquals(JsonObject.of("a", JsonNumber.of(1)), JsonObject.of("a", JsonNumber.of(1), "b", JsonNull.INSTANCE));
    }

    @Test
    void valuesOfDifferentKindsAreNeverEqual() {
        List<JsonValue> one = List.of(JsonNull.INSTANCE, JsonBoolean.TRUE, JsonNumber.of(1), new JsonString("1"),
            JsonArray.of(JsonNumber.of(1)), JsonObject.of("1", JsonNumber.of(1)));
        for (int i = 0; i < one.size(); i++) {
            for (int j = 0; j < one.size(); j++) {
                assertEquals(i == j, one.get(i).equals(one.get(j)), i + " vs " + j);
            }
        }
    }

    // ------------------------------------------------------------------ the convenience methods on JsonValue

    @Test
    void toJsonAndToPrettyJsonAreTheWriter() {
        JsonValue value = JsonObject.of("a", JsonArray.of(JsonNumber.of(1)));
        assertEquals("{\"a\":[1]}", value.toJson());
        assertEquals("{\n  \"a\": [\n    1\n  ]\n}", value.toPrettyJson(2));
        assertEquals(value.toJson(), value.toPrettyJson(0));
    }
}
