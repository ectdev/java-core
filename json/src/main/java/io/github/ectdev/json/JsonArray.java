package io.github.ectdev.json;

import java.util.List;

/** A JSON array. The list is copied on construction and cannot be changed afterwards. */
public record JsonArray(List<JsonValue> items) implements JsonValue {

    public JsonArray {
        items = List.copyOf(items); // immutable, and rejects null elements
    }

    public static JsonArray of(JsonValue... items) {
        return new JsonArray(List.of(items));
    }
}
