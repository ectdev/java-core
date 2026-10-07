package io.github.ectdev.json;

import java.util.Optional;

/**
 * A JSON value, modelled as a closed set of cases.
 *
 * <p>JSON has exactly six kinds of value, so this is a {@code sealed} interface with exactly six implementations,
 * each a {@code record}. Closing the set is what makes code over it safe: a {@code switch} over a {@code JsonValue}
 * that handles all six cases needs no {@code default} branch, and the compiler rejects it if a case is missing. Add a
 * seventh kind of value one day and every such {@code switch} stops compiling, instead of falling silently into a
 * {@code default} that was written before the new case existed. {@link JsonWriter} and {@link JsonPointer} are
 * written that way.
 *
 * <p>All implementations are immutable and compare by value: two parsed documents are {@code equals} if they hold the
 * same data, regardless of how the source text spaced them out or in which order an object listed its members.
 */
public sealed interface JsonValue
    permits JsonNull, JsonBoolean, JsonNumber, JsonString, JsonArray, JsonObject {

    /** The compact JSON text for this value. */
    default String toJson() {
        return JsonWriter.write(this);
    }

    /** Indented JSON text, {@code indent} spaces per level; {@code indent} 0 is the same as {@link #toJson()}. */
    default String toPrettyJson(int indent) {
        return JsonWriter.write(this, indent);
    }

    /**
     * The value at an RFC 6901 JSON Pointer, for example {@code "/Image/IDs/0"}, or empty if the pointer leads
     * nowhere.
     *
     * @throws IllegalArgumentException if the pointer itself is malformed
     */
    default Optional<JsonValue> at(String pointer) {
        return JsonPointer.evaluate(this, pointer);
    }
}
