package io.github.ectdev.json;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A JSON number, held as a {@link BigDecimal}.
 *
 * <p>JSON's grammar says nothing about precision, and the common mistake is to read every number as a {@code double}:
 * {@code 0.1} then becomes 0.1000000000000000055511151231257827…, and an integer above 2⁵³ silently changes. A
 * {@code BigDecimal} holds exactly the digits that were written, so {@code 0.1} is one tenth and a thirty-digit
 * identifier survives. The price is that {@code equals} is scale-sensitive: {@code 1.0} and {@code 1.00} are different
 * values here, as they are for {@code BigDecimal}, because they record different amounts of written precision. Use
 * {@code compareTo} on {@link #value()} to compare numerically.
 *
 * <p>One value cannot be represented: {@code -0}. A {@code BigDecimal} has no negative zero, so the text {@code -0}
 * is read as 0 and written back as {@code 0}.
 */
public record JsonNumber(BigDecimal value) implements JsonValue {

    public JsonNumber {
        Objects.requireNonNull(value, "value");
    }

    public static JsonNumber of(long value) {
        return new JsonNumber(BigDecimal.valueOf(value));
    }

    /** @param decimal a number in any form {@link BigDecimal#BigDecimal(String)} accepts */
    public static JsonNumber of(String decimal) {
        return new JsonNumber(new BigDecimal(decimal));
    }
}
