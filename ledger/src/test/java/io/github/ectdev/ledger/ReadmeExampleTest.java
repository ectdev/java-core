package io.github.ectdev.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The snippets at the top of the module README, run as written, with their comments turned into assertions. */
class ReadmeExampleTest {

    @Test
    void theLotBookSnippet() {
        Currency usd = Currency.getInstance("USD");
        LotBook book = new LotBook(usd);
        book.buy(new BigDecimal("10"), new BigDecimal("100"), Money.zero(usd));
        book.buy(new BigDecimal("10"), new BigDecimal("200"), Money.zero(usd));

        Sale sale = book.sell(new BigDecimal("12"), new BigDecimal("300"), Money.zero(usd));
        assertEquals("2200.00 USD", sale.realized().toString());
        assertEquals("[Lot[quantity=8, cost=1600.00 USD]]", book.lots().toString());

        InsufficientQuantityException e = assertThrows(InsufficientQuantityException.class,
            () -> book.sell(new BigDecimal("9"), new BigDecimal("300"), Money.zero(usd)));
        assertEquals("cannot sell 9: only 8 held (short by 1)", e.getMessage());
    }

    @Test
    void theAllocationSnippet() {
        Currency usd = Currency.getInstance("USD");
        assertEquals("[0.34 USD, 0.33 USD, 0.33 USD]", Money.parse("1.00", usd).split(3).toString());
        assertEquals("[0.04 USD, 0.01 USD]", Money.parse("0.05", usd).allocate(70, 30).toString());
    }

    @Test
    void theTimelineSnippet() {
        Currency usd = Currency.getInstance("USD");
        Money none = Money.zero(usd);
        List<Trade> history = List.of(
            Trade.buy(Instant.parse("2026-03-03T10:00:00Z"), "10", "100", none),
            Trade.sell(Instant.parse("2026-03-02T10:00:00Z"), "5", "150", none));

        TradeJournal.ShortSaleException e = assertThrows(TradeJournal.ShortSaleException.class,
            () -> TradeJournal.replay(usd, history));
        assertEquals("the sale at 2026-03-02T10:00:00Z asks for 5 but only 0 was held then (short by 5)", e.getMessage());
    }
}
