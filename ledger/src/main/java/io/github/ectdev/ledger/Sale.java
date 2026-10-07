package io.github.ectdev.ledger;

import java.math.BigDecimal;
import java.util.List;

/**
 * What one sale did: what came in, what it cost to get the thing sold, and which lots paid for it.
 *
 * @param proceeds   quantity times price, rounded to the minor unit, before the fee
 * @param fee        what the sale itself cost
 * @param costOfSold the cost of the lots consumed, in the order they were consumed (oldest first)
 * @param consumed   the part of each lot that was used
 */
public record Sale(Money proceeds, Money fee, Money costOfSold, List<Consumption> consumed) {

    public Sale {
        consumed = List.copyOf(consumed);
    }

    /** What was made on the sale: proceeds, less the fee, less what the sold quantity cost. */
    public Money realized() {
        return proceeds.minus(fee).minus(costOfSold);
    }

    /** The total quantity sold. */
    public BigDecimal quantity() {
        BigDecimal total = BigDecimal.ZERO;
        for (Consumption part : consumed) {
            total = total.add(part.quantity());
        }
        return Lot.canonical(total);
    }

    /** The part of one lot that a sale consumed, and the share of that lot's cost it carried. */
    public record Consumption(BigDecimal quantity, Money cost) { }
}
