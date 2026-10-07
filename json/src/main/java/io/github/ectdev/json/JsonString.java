package io.github.ectdev.json;

import java.util.Objects;

/**
 * A JSON string.
 *
 * <p>The text must be well-formed Unicode: a Java {@code String} can hold an unpaired surrogate (half of a character
 * outside the Basic Multilingual Plane), but that has no UTF-8 encoding, so it cannot be written to a file or sent
 * over a wire without being changed. Rejecting it here, once, means that every {@code JsonString} that exists can be
 * written out and read back as the same value.
 */
public record JsonString(String value) implements JsonValue {

    public JsonString {
        Objects.requireNonNull(value, "value");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++; // a valid pair: skip its second half
            } else if (Character.isSurrogate(c)) {
                throw new IllegalArgumentException(
                    "unpaired surrogate U+%04X at index %d: not well-formed Unicode".formatted((int) c, i));
            }
        }
    }
}
