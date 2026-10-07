package io.github.ectdev.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * The purchases of one instrument that are still held, oldest first, and the rule for what a sale consumes: first in,
 * first out. A sale takes from the head of the queue, using whole lots until the last one, which it may use only in
 * part.
 *
 * <p>Money handling, all of it checked by the tests:
 *
 * <ul>
 *   <li>A purchase's cost is {@code quantity x price}, rounded half-even to the minor unit, plus the fee. The fee is
 *       part of the lot, so it is recovered pro rata as the lot is sold.
 *   <li>A partly used lot hands the sale {@code lot cost x used / lot quantity}, rounded half-even to the minor
 *       unit, and keeps the rest: the lot's cost is split in two integers that add up to the original, so no unit is
 *       created or lost however many partial sales follow. The total of everything sold plus everything still held
 *       always equals the total of everything bought.
 *   <li>A sale's proceeds are {@code quantity x price}, rounded half-even to the minor unit.
 * </ul>
 *
 * <p>A sale of more than is held throws {@link InsufficientQuantityException} and changes nothing. So does any other
 * failure: every operation computes its result first and replaces the book's state in one assignment at the end,
 * so a failure half-way (a numeric overflow, say) cannot leave a half-consumed queue behind.
 *
 * <p>Not thread-safe; guard a shared book externally.
 */
public final class LotBook {

    private final Currency currency;
    private List<Lot> lots = List.of();

    public LotBook(Currency currency) {
        this.currency = Objects.requireNonNull(currency, "currency");
        Money.zero(currency); // fails now, not at the first purchase, for a currency with no minor unit
    }

    public Currency currency() {
        return currency;
    }

    /** A snapshot of the lots held, oldest first. */
    public List<Lot> lots() {
        return lots;
    }

    /** The total quantity held. */
    public BigDecimal quantity() {
        BigDecimal total = BigDecimal.ZERO;
        for (Lot lot : lots) {
            total = total.add(lot.quantity());
        }
        return Lot.canonical(total);
    }

    /** What the quantity held cost, fees included. */
    public Money costBasis() {
        Money total = Money.zero(currency);
        for (Lot lot : lots) {
            total = total.plus(lot.cost());
        }
        return total;
    }

    /**
     * Adds a lot at the back of the queue.
     *
     * @param quantity  greater than zero
     * @param unitPrice not negative; in major units (dollars, not cents) and may have more decimals than the currency
     * @param fee       not negative; paid on top of the price and carried by the lot
     */
    public void buy(BigDecimal quantity, BigDecimal unitPrice, Money fee) {
        BigDecimal amount = positive(quantity, "quantity");
        requireNonNegativePrice(unitPrice);
        requireFee(fee);
        Money cost = Money.of(amount.multiply(unitPrice), currency, RoundingMode.HALF_EVEN).plus(fee);

        List<Lot> next = new ArrayList<>(lots);
        next.add(new Lot(amount, cost));
        lots = List.copyOf(next);
    }

    /**
     * Sells from the oldest lots.
     *
     * @param quantity  greater than zero, and not more than {@link #quantity()}
     * @param unitPrice not negative, in major units
     * @param fee       not negative
     * @throws InsufficientQuantityException if more is asked for than is held
     */
    public Sale sell(BigDecimal quantity, BigDecimal unitPrice, Money fee) {
        BigDecimal wanted = positive(quantity, "quantity");
        requireNonNegativePrice(unitPrice);
        requireFee(fee);
        BigDecimal held = quantity();
        if (wanted.compareTo(held) > 0) {
            throw new InsufficientQuantityException(wanted, held);
        }
        Money proceeds = Money.of(wanted.multiply(unitPrice), currency, RoundingMode.HALF_EVEN);

        List<Lot> remaining = new ArrayList<>();
        List<Sale.Consumption> consumed = new ArrayList<>();
        Money costOfSold = Money.zero(currency);
        BigDecimal toTake = wanted;
        for (Lot lot : lots) {
            if (toTake.signum() == 0) {
                remaining.add(lot);
            } else if (lot.quantity().compareTo(toTake) <= 0) {
                consumed.add(new Sale.Consumption(lot.quantity(), lot.cost()));
                costOfSold = costOfSold.plus(lot.cost());
                toTake = toTake.subtract(lot.quantity());
            } else {
                // The last lot is used in part: the sale gets a rounded share of its cost, the lot keeps the rest.
                long shareMinor = BigDecimal.valueOf(lot.cost().minor())
                    .multiply(toTake)
                    .divide(lot.quantity(), 0, RoundingMode.HALF_EVEN)
                    .longValueExact();
                Money share = Money.ofMinor(shareMinor, currency);
                consumed.add(new Sale.Consumption(toTake, share));
                costOfSold = costOfSold.plus(share);
                remaining.add(new Lot(lot.quantity().subtract(toTake), lot.cost().minus(share)));
                toTake = BigDecimal.ZERO;
            }
        }

        Sale sale = new Sale(proceeds, fee, costOfSold, consumed);
        sale.realized(); // surfaces an overflow now, while the book is still untouched
        lots = List.copyOf(remaining);
        return sale;
    }

    private BigDecimal positive(BigDecimal quantity, String what) {
        Objects.requireNonNull(quantity, what);
        BigDecimal canonical = Lot.canonical(quantity);
        if (canonical.signum() <= 0) {
            throw new IllegalArgumentException(what + " must be greater than zero: " + quantity.toPlainString());
        }
        return canonical;
    }

    private static void requireNonNegativePrice(BigDecimal unitPrice) {
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice must not be negative: " + unitPrice.toPlainString());
        }
    }

    private void requireFee(Money fee) {
        Objects.requireNonNull(fee, "fee");
        if (!fee.currency().equals(currency)) {
            throw new IllegalArgumentException(
                "fee is in " + fee.currency().getCurrencyCode() + " but the book is in " + currency.getCurrencyCode());
        }
        if (fee.isNegative()) {
            throw new IllegalArgumentException("fee must not be negative: " + fee);
        }
    }
}
