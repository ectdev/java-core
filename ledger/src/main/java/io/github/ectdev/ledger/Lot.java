package io.github.ectdev.ledger;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One purchase still (partly) held: how much is left of it and what that remainder cost, fees included.
 *
 * <p>The cost is a {@link Money}, a whole number of minor units, and not a unit price: a unit price such as
 * 100.3333… has no exact decimal form, whereas "the remaining 3 of this lot cost 301.00" always does.
 *
 * <p>The quantity is stored in a canonical form (no trailing zeros, never a negative scale), so that
 * {@code 10}, {@code 10.0} and {@code 1E+1} are one value as far as {@code equals} is concerned. A plain
 * {@link BigDecimal} would call them three.
 */
public record Lot(BigDecimal quantity, Money cost) {

    public Lot {
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(cost, "cost");
        quantity = canonical(quantity);
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("a lot holds a positive quantity: " + quantity.toPlainString());
        }
        if (cost.isNegative()) {
            throw new IllegalArgumentException("a lot cannot have a negative cost: " + cost);
        }
    }

    /** {@code 10}, {@code 10.0} and {@code 1E+1} all become {@code 10}; {@code 0.50} becomes {@code 0.5}. */
    static BigDecimal canonical(BigDecimal quantity) {
        if (quantity.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal stripped = quantity.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
