package io.github.ectdev.json;

/** The JSON literal {@code null}. It has no data, so every instance is equal to every other. */
public record JsonNull() implements JsonValue {

    public static final JsonNull INSTANCE = new JsonNull();
}
