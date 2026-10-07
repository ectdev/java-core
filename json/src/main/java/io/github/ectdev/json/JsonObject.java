package io.github.ectdev.json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A JSON object. Members keep the order they were written in, which matters when a document is read and written
 * back, but equality ignores the order: {@code {"a":1,"b":2}} and {@code {"b":2,"a":1}} are the same object, as
 * RFC 8259 defines an object to be an unordered collection of members.
 *
 * <p>The map is copied on construction and cannot be changed afterwards.
 */
public record JsonObject(Map<String, JsonValue> members) implements JsonValue {

    public JsonObject {
        Map<String, JsonValue> copy = new LinkedHashMap<>();
        members.forEach((name, value) -> copy.put(Objects.requireNonNull(name, "member name"), Objects.requireNonNull(value, "member value")));
        members = Collections.unmodifiableMap(copy);
    }

    /** Builds an object from alternating names and values: {@code of("a", one, "b", two)}. */
    public static JsonObject of(Object... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("expected alternating names and values, got " + namesAndValues.length + " arguments");
        }
        Map<String, JsonValue> members = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            if (members.put((String) namesAndValues[i], (JsonValue) namesAndValues[i + 1]) != null) {
                throw new IllegalArgumentException("duplicate member name: " + namesAndValues[i]);
            }
        }
        return new JsonObject(members);
    }
}
