package io.github.ectdev.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Hand-worked histories. Days are 1 to 9 March 2026, all at 10:00 UTC; amounts in the assertions are cents. */
class TradeJournalTest {

    private static final Currency USD = MoneyTest.USD;
    private static final Money NO_FEE = Money.zero(USD);

    private static Instant day(int day) {
        return Instant.parse("2026-03-0" + day + "T10:00:00Z");
    }

    private static Money usd(long cents) {
        return Money.ofMinor(cents, USD);
    }

    private static Trade buy(int day, String quantity, String price) {
        return Trade.buy(day(day), quantity, price, NO_FEE);
    }

    private static Trade sell(int day, String quantity, String price) {
        return Trade.sell(day(day), quantity, price, NO_FEE);
    }

    @Test
    void anEmptyHistoryIsAnEmptyBook() {
        TradeJournal.Replay replay = TradeJournal.replay(USD, List.of());
        assertEquals(List.of(), replay.book().lots());
        assertEquals(List.of(), replay.sales());
        assertEquals(usd(0), replay.realized(USD));
    }

    @Test
    void tradesAreAppliedInTheOrderTheyHappenedNotTheOrderTheyWereListed() {
        // Listed sale first, purchase second; by date the purchase comes first.
        TradeJournal.Replay replay = TradeJournal.replay(USD, List.of(sell(3, "5", "150"), buy(1, "10", "100")));

        assertEquals(1, replay.sales().size());
        assertEquals(usd(25_000), replay.sales().get(0).realized());   // 5 x 150 = 750 in; 5 x 100 = 500 out; 250
        assertEquals(usd(25_000), replay.realized(USD));
        assertEquals(List.of(new Lot(new BigDecimal("5"), usd(50_000))), replay.book().lots());
    }

    @Test
    void theInputListIsNeitherChangedNorRequiredToBeModifiable() {
        List<Trade> trades = List.of(sell(3, "5", "150"), buy(1, "10", "100"));   // immutable, and out of order
        TradeJournal.replay(USD, trades);
        assertEquals(sell(3, "5", "150"), trades.get(0));
    }

    @Test
    void aWorkedHistoryWithTwoLotsAndTwoSales() {
        TradeJournal.Replay replay = TradeJournal.replay(USD, List.of(
            buy(1, "10", "100"),      // A: 10 at 100 = 1,000.00
            sell(2, "4", "120"),      // 480 in; 4 of A cost 400; realized 80.00
            buy(3, "10", "200"),      // B: 10 at 200 = 2,000.00
            sell(4, "10", "250")));   // 2,500 in; the 6 left of A cost 600, 4 of B cost 800; realized 1,100.00

        assertEquals(2, replay.sales().size());
        assertEquals(usd(8_000), replay.sales().get(0).realized());
        assertEquals(usd(110_000), replay.sales().get(1).realized());
        assertEquals(usd(118_000), replay.realized(USD));
        assertEquals(List.of(new Lot(new BigDecimal("6"), usd(120_000))), replay.book().lots());   // 6 of B left, 1,200.00
    }

    // ------------------------------------------------------------------ the timeline must never go short

    @Test
    void aBackDatedSaleBeforeAnyPurchaseIsRefusedAndNamesTheMoment() {
        TradeJournal.ShortSaleException e = assertThrows(TradeJournal.ShortSaleException.class,
            () -> TradeJournal.replay(USD, List.of(buy(3, "10", "100"), sell(2, "5", "150"))));

        assertEquals(day(2), e.at());
        assertEquals(new BigDecimal("5"), e.shortBy());
        assertEquals("the sale at 2026-03-02T10:00:00Z asks for 5 but only 0 was held then (short by 5)", e.getMessage());
    }

    @Test
    void aFinalPositionOfZeroDoesNotExcuseGoingShortOnTheWay() {
        // Sell 10 on the 2nd, buy 10 on the 3rd: the end result is a clean zero, but on the 2nd nothing was owned.
        TradeJournal.ShortSaleException e = assertThrows(TradeJournal.ShortSaleException.class,
            () -> TradeJournal.replay(USD, List.of(buy(3, "10", "100"), sell(2, "10", "100"))));
        assertEquals(day(2), e.at());
        assertEquals(new BigDecimal("10"), e.shortBy());
    }

    @Test
    void deletingAPurchaseFromAValidHistoryIsRefusedWhereItFirstMatters() {
        Trade first = buy(1, "10", "100");
        Trade firstSale = sell(2, "6", "110");
        Trade second = buy(3, "4", "100");
        Trade secondSale = sell(4, "8", "120");

        // As recorded: 10, then 4 after the first sale, then 8 after the second purchase, then 0.
        TradeJournal.Replay whole = TradeJournal.replay(USD, List.of(first, firstSale, second, secondSale));
        assertEquals(List.of(), whole.book().lots());

        // Without the first purchase, the sale on the 2nd has nothing to sell.
        TradeJournal.ShortSaleException withoutFirst = assertThrows(TradeJournal.ShortSaleException.class,
            () -> TradeJournal.replay(USD, List.of(firstSale, second, secondSale)));
        assertEquals(day(2), withoutFirst.at());
        assertEquals(new BigDecimal("6"), withoutFirst.shortBy());

        // Without the second purchase, the sale on the 4th asks for 8 with 4 held.
        TradeJournal.ShortSaleException withoutSecond = assertThrows(TradeJournal.ShortSaleException.class,
            () -> TradeJournal.replay(USD, List.of(first, firstSale, secondSale)));
        assertEquals(day(4), withoutSecond.at());
        assertEquals(new BigDecimal("4"), withoutSecond.shortBy());
    }

    @Test
    void aPurchaseListedBeforeASaleAtTheSameInstantCoversIt() {
        TradeJournal.Replay replay = TradeJournal.replay(USD, List.of(buy(1, "5", "10"), sell(1, "5", "12")));
        assertEquals(usd(1_000), replay.realized(USD));   // 60 in, 50 out
        assertEquals(List.of(), replay.book().lots());
    }

    @Test
    void aSaleListedBeforeAPurchaseAtTheSameInstantDoesNot() {
        TradeJournal.ShortSaleException e = assertThrows(TradeJournal.ShortSaleException.class,
            () -> TradeJournal.replay(USD, List.of(sell(1, "5", "12"), buy(1, "5", "10"))));
        assertEquals(day(1), e.at());
    }

    @Test
    void tradesAtTheSameInstantKeepTheirListedOrder() {
        // Two sales at one instant: the first listed takes from the oldest lot, the second from what is left.
        TradeJournal.Replay replay = TradeJournal.replay(USD, List.of(
            buy(1, "2", "10"), buy(2, "2", "20"), sell(3, "1", "30"), sell(3, "2", "30")));
        assertEquals(usd(1_000), replay.sales().get(0).costOfSold());                       // 1 unit of the first lot, at 10
        assertEquals(usd(3_000), replay.sales().get(1).costOfSold());                       // its last unit (10), then 1 of the 20s
        assertEquals(List.of(new Lot(new BigDecimal("1"), usd(2_000))), replay.book().lots());
    }

    @Test
    void feesOnBothSidesCarryThrough() {
        TradeJournal.Replay replay = TradeJournal.replay(USD, List.of(
            Trade.buy(day(1), "10", "100", usd(500)),     // 1,005.00
            Trade.sell(day(2), "10", "110", usd(300))));  // 1,100 in, 3 fee, 1,005 out
        assertEquals(usd(9_200), replay.realized(USD));   // 1,100 - 3 - 1,005 = 92.00
    }

    // ------------------------------------------------------------------ bad input

    @Test
    void nullArgumentsAreProgrammingErrors() {
        assertThrows(NullPointerException.class, () -> TradeJournal.replay(null, List.of()));
        assertThrows(NullPointerException.class, () -> TradeJournal.replay(USD, null));
        List<Trade> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> TradeJournal.replay(USD, withNull));
        assertThrows(NullPointerException.class, () -> new Trade(null, Trade.Side.BUY, BigDecimal.ONE, BigDecimal.ONE, NO_FEE));
        assertThrows(NullPointerException.class, () -> new Trade(day(1), null, BigDecimal.ONE, BigDecimal.ONE, NO_FEE));
    }

    @Test
    void aTradeThatBreaksABookRuleIsReportedAsThatRuleNotAsAShortSale() {
        assertThrows(IllegalArgumentException.class, () -> TradeJournal.replay(USD, List.of(buy(1, "0", "100"))));
        assertThrows(IllegalArgumentException.class, () -> TradeJournal.replay(USD, List.of(buy(1, "1", "-100"))));
        assertThrows(IllegalArgumentException.class,
            () -> TradeJournal.replay(USD, List.of(Trade.buy(day(1), "1", "1", Money.zero(MoneyTest.EUR)))));
    }
}
