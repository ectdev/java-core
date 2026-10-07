package io.github.ectdev.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Replays a list of trades in the order they happened and refuses a history in which the position would go below
 * zero at any moment.
 *
 * <p>The check has to be made on the whole timeline, not on the final quantity. Entering a sale dated last Tuesday,
 * or deleting an old purchase, can leave the end result looking fine (zero held, say) while on Wednesday the book
 * sold something it did not yet own, and the profit worked out from that is invented. So the entire history is
 * replayed on every change; if a sale at any point asks for more than was held at that point, the whole history is
 * refused and the message names the moment and the shortfall.
 *
 * <p>Trades are applied in order of {@code at}. Trades at the same instant keep the order in which they were listed,
 * so a purchase listed before a sale at the same instant covers it, and the other way round does not.
 */
public final class TradeJournal {

    private TradeJournal() { }

    /** The book after the replay and the sales it produced, in the order they happened. */
    public record Replay(LotBook book, List<Sale> sales) {

        public Replay {
            sales = List.copyOf(sales);
        }

        /** The sum of what the sales realized. */
        public Money realized(Currency currency) {
            Money total = Money.zero(currency);
            for (Sale sale : sales) {
                total = total.plus(sale.realized());
            }
            return total;
        }
    }

    /** Thrown when a history would have sold more than was held at that moment. */
    public static final class ShortSaleException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient Instant at;
        private final transient BigDecimal shortBy;

        ShortSaleException(Instant at, InsufficientQuantityException cause) {
            super("the sale at " + at + " asks for " + cause.requested().toPlainString() + " but only "
                + cause.held().toPlainString() + " was held then (short by " + cause.shortBy().toPlainString() + ")", cause);
            this.at = at;
            this.shortBy = cause.shortBy();
        }

        /** When the position would have gone below zero. */
        public Instant at() {
            return at;
        }

        public BigDecimal shortBy() {
            return shortBy;
        }
    }

    /**
     * @throws ShortSaleException if a sale, at the moment it happened, exceeds what was held
     */
    public static Replay replay(Currency currency, List<Trade> trades) {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(trades, "trades");
        List<Trade> ordered = new ArrayList<>(trades);
        ordered.sort(Comparator.comparing(Trade::at)); // List.sort is stable: equal instants keep their listed order

        LotBook book = new LotBook(currency);
        List<Sale> sales = new ArrayList<>();
        for (Trade trade : ordered) {
            switch (trade.side()) {
                case BUY -> book.buy(trade.quantity(), trade.unitPrice(), trade.fee());
                case SELL -> {
                    try {
                        sales.add(book.sell(trade.quantity(), trade.unitPrice(), trade.fee()));
                    } catch (InsufficientQuantityException e) {
                        throw new ShortSaleException(trade.at(), e);
                    }
                }
            }
        }
        return new Replay(book, sales);
    }
}
