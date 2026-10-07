package io.github.ectdev.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Hand-worked cases. Amounts are written in cents in the assertions, with the dollars in a comment, so that every
 * number can be followed on paper.
 */
class LotBookTest {

    private static final Currency USD = MoneyTest.USD;
    private static final Currency JPY = MoneyTest.JPY;
    private static final Money NO_FEE = Money.zero(USD);

    private static BigDecimal d(String text) {
        return new BigDecimal(text);
    }

    private static Money usd(long cents) {
        return Money.ofMinor(cents, USD);
    }

    private static Lot lot(String quantity, long cents) {
        return new Lot(d(quantity), usd(cents));
    }

    // ------------------------------------------------------------------ the textbook case

    @Test
    void tenAtOneHundredAndTenAtTwoHundredThenSellTwelveAtThreeHundred() {
        LotBook book = new LotBook(USD);
        book.buy(d("10"), d("100"), NO_FEE);
        book.buy(d("10"), d("200"), NO_FEE);
        assertEquals(List.of(lot("10", 100_000), lot("10", 200_000)), book.lots());
        assertEquals(d("20"), book.quantity());
        assertEquals(usd(300_000), book.costBasis());                  // 1,000 + 2,000

        Sale sale = book.sell(d("12"), d("300"), NO_FEE);

        assertEquals(usd(360_000), sale.proceeds());                   // 12 x 300 = 3,600
        assertEquals(usd(140_000), sale.costOfSold());                 // all 10 at 100, then 2 of the 10 at 200: 1,000 + 400
        assertEquals(usd(220_000), sale.realized());                   // 3,600 - 1,400 = 2,200
        assertEquals(d("12"), sale.quantity());
        assertEquals(List.of(new Sale.Consumption(d("10"), usd(100_000)), new Sale.Consumption(d("2"), usd(40_000))), sale.consumed());
        assertEquals(List.of(lot("8", 160_000)), book.lots());         // 8 left at 200 = 1,600
        assertEquals(d("8"), book.quantity());
        assertEquals(usd(160_000), book.costBasis());
    }

    @Test
    void feesAreCarriedByTheLotAndRecoveredAsItIsSold() {
        LotBook book = new LotBook(USD);
        book.buy(d("10"), d("100"), usd(500));                         // 1,000 + 5 fee = 1,005
        assertEquals(List.of(lot("10", 100_500)), book.lots());

        Sale sale = book.sell(d("4"), d("150"), usd(200));             // 600 in, 2 fee

        assertEquals(usd(60_000), sale.proceeds());
        assertEquals(usd(40_200), sale.costOfSold());                  // 1,005 x 4 / 10 = 402
        assertEquals(usd(19_600), sale.realized());                    // 600 - 2 - 402 = 196
        assertEquals(List.of(lot("6", 60_300)), book.lots());          // 1,005 - 402 = 603
    }

    @Test
    void sellingAtALossIsNegative() {
        LotBook book = new LotBook(USD);
        book.buy(d("10"), d("100"), NO_FEE);
        Sale sale = book.sell(d("10"), d("90"), usd(100));             // 900.00 in, 1.00 fee, 1,000.00 out
        assertEquals(usd(-10_100), sale.realized());                   // 900 - 1 - 1,000 = -101
    }

    // ------------------------------------------------------------------ rounding keeps every cent

    @Test
    void aLotSoldInThreePartsGivesBackExactlyWhatItCost() {
        LotBook book = new LotBook(USD);
        book.buy(d("3"), d("10"), usd(1));                              // 30.00 + 0.01 = 30.01 = 3,001 cents

        // 3,001 x 1 / 3 = 1,000.33 -> 1,000
        assertEquals(usd(1_000), book.sell(d("1"), d("12"), NO_FEE).costOfSold());
        assertEquals(List.of(lot("2", 2_001)), book.lots());
        // 2,001 x 1 / 2 = 1,000.5, a tie -> the even neighbour, 1,000
        assertEquals(usd(1_000), book.sell(d("1"), d("12"), NO_FEE).costOfSold());
        assertEquals(List.of(lot("1", 1_001)), book.lots());
        // the last unit takes the whole remainder: no rounding is left to do
        assertEquals(usd(1_001), book.sell(d("1"), d("12"), NO_FEE).costOfSold());
        assertEquals(List.of(), book.lots());
        // 1,000 + 1,000 + 1,001 = 3,001
    }

    @Test
    void aTieRoundsToTheEvenNeighbourInBothDirections() {
        LotBook down = new LotBook(USD);
        down.buy(d("2"), d("10"), usd(1));                              // 2,001 cents; half of it is 1,000.5 -> 1,000
        assertEquals(usd(1_000), down.sell(d("1"), d("1"), NO_FEE).costOfSold());

        LotBook up = new LotBook(USD);
        up.buy(d("2"), d("10"), usd(3));                                // 2,003 cents; half is 1,001.5 -> 1,002
        assertEquals(usd(1_002), up.sell(d("1"), d("1"), NO_FEE).costOfSold());
        assertEquals(List.of(lot("1", 1_001)), up.lots());
    }

    @Test
    void thePriceMayHaveMoreDecimalsThanTheCurrencyAndIsRoundedOnce() {
        LotBook book = new LotBook(USD);
        book.buy(d("3"), d("0.3333"), NO_FEE);                          // 0.9999 -> 99.99 cents -> 100
        book.buy(d("1"), d("0.125"), NO_FEE);                           // 12.5 cents -> 12 (even)
        book.buy(d("1"), d("0.135"), NO_FEE);                           // 13.5 cents -> 14 (even)
        assertEquals(List.of(lot("3", 100), lot("1", 12), lot("1", 14)), book.lots());
    }

    @Test
    void proceedsAreRoundedHalfEvenToo() {
        LotBook book = new LotBook(USD);
        book.buy(d("10"), d("1"), NO_FEE);
        assertEquals(usd(12), book.sell(d("1"), d("0.125"), NO_FEE).proceeds());   // 12.5 -> 12
        assertEquals(usd(14), book.sell(d("1"), d("0.135"), NO_FEE).proceeds());   // 13.5 -> 14
    }

    @Test
    void aCurrencyWithNoDecimalsWorksInWholeUnits() {
        LotBook book = new LotBook(JPY);
        book.buy(d("3"), d("100"), Money.zero(JPY));                    // 300 yen
        Sale sale = book.sell(d("2"), d("150"), Money.zero(JPY));       // 300 in; cost 300 x 2 / 3 = 200
        assertEquals(Money.ofMinor(200, JPY), sale.costOfSold());
        assertEquals(Money.ofMinor(100, JPY), sale.realized());
        assertEquals(List.of(new Lot(d("1"), Money.ofMinor(100, JPY))), book.lots());
    }

    // ------------------------------------------------------------------ quantities

    @Test
    void fractionalQuantitiesWork() {
        LotBook book = new LotBook(USD);
        book.buy(d("0.5"), d("20000"), NO_FEE);                         // 10,000.00
        Sale sale = book.sell(d("0.2"), d("30000"), NO_FEE);            // 6,000.00 in; cost 10,000 x 0.2 / 0.5 = 4,000

        assertEquals(usd(600_000), sale.proceeds());
        assertEquals(usd(400_000), sale.costOfSold());
        assertEquals(usd(200_000), sale.realized());
        assertEquals(List.of(lot("0.3", 600_000)), book.lots());
        assertEquals(d("0.3"), book.quantity());
    }

    @Test
    void aQuantityIsTheSameWhicheverWayItIsWritten() {
        LotBook book = new LotBook(USD);
        book.buy(d("10.0"), d("1"), NO_FEE);
        book.buy(d("1E+1"), d("1"), NO_FEE);
        book.buy(d("10.00"), d("1"), NO_FEE);
        assertEquals(List.of(lot("10", 1_000), lot("10", 1_000), lot("10", 1_000)), book.lots());
        assertEquals(d("10"), book.lots().get(1).quantity());
        assertEquals(0, book.lots().get(1).quantity().scale());
        assertEquals(d("30"), book.quantity());
        assertEquals(0, book.quantity().scale());
        // Selling "10.000" when 10 is held is the whole lot, whatever the scale.
        book.sell(d("10.000"), d("1"), NO_FEE);
        assertEquals(2, book.lots().size());
    }

    @Test
    void anEmptyBookHoldsNothingAndCostsNothing() {
        LotBook book = new LotBook(USD);
        assertEquals(List.of(), book.lots());
        assertEquals(d("0"), book.quantity());
        assertEquals(usd(0), book.costBasis());
        assertEquals(USD, book.currency());
    }

    @Test
    void sellingEverythingEmptiesTheBook() {
        LotBook book = new LotBook(USD);
        book.buy(d("5"), d("10"), NO_FEE);
        book.buy(d("5"), d("20"), NO_FEE);
        Sale sale = book.sell(d("10"), d("30"), NO_FEE);
        assertEquals(usd(15_000), sale.costOfSold());
        assertEquals(List.of(), book.lots());
        assertEquals(d("0"), book.quantity());
        assertEquals(usd(0), book.costBasis());
    }

    // ------------------------------------------------------------------ first in, first out

    @Test
    void aPartlyUsedLotKeepsItsPlaceAtTheHeadOfTheQueue() {
        LotBook book = new LotBook(USD);
        book.buy(d("5"), d("10"), NO_FEE);                              // A: 5 at 10 = 50.00
        book.buy(d("5"), d("20"), NO_FEE);                              // B: 5 at 20 = 100.00
        book.buy(d("5"), d("30"), NO_FEE);                              // C: 5 at 30 = 150.00

        Sale first = book.sell(d("7"), d("40"), NO_FEE);                // all of A, 2 of B (100 x 2 / 5 = 40)
        assertEquals(List.of(new Sale.Consumption(d("5"), usd(5_000)), new Sale.Consumption(d("2"), usd(4_000))), first.consumed());
        assertEquals(List.of(lot("3", 6_000), lot("5", 15_000)), book.lots());

        Sale second = book.sell(d("4"), d("40"), NO_FEE);               // the 3 left of B, then 1 of C (150 x 1 / 5 = 30)
        assertEquals(List.of(new Sale.Consumption(d("3"), usd(6_000)), new Sale.Consumption(d("1"), usd(3_000))), second.consumed());
        assertEquals(usd(9_000), second.costOfSold());
        assertEquals(List.of(lot("4", 12_000)), book.lots());
    }

    @Test
    void aSaleSmallerThanTheHeadLotLeavesTheOthersUntouched() {
        LotBook book = new LotBook(USD);
        book.buy(d("10"), d("10"), NO_FEE);
        book.buy(d("10"), d("20"), NO_FEE);
        book.sell(d("1"), d("5"), NO_FEE);
        assertEquals(List.of(lot("9", 9_000), lot("10", 20_000)), book.lots());
    }

    @Test
    void aFreeLotIsAllowedAndCostsNothing() {
        LotBook book = new LotBook(USD);
        book.buy(d("5"), d("0"), NO_FEE);
        assertEquals(List.of(lot("5", 0)), book.lots());
        Sale sale = book.sell(d("5"), d("10"), NO_FEE);
        assertEquals(usd(5_000), sale.realized());
    }

    // ------------------------------------------------------------------ refusing a sale that cannot be made

    @Test
    void sellingMoreThanIsHeldIsRefusedAndChangesNothing() {
        LotBook book = new LotBook(USD);
        book.buy(d("10"), d("100"), usd(500));
        List<Lot> before = book.lots();

        InsufficientQuantityException e = assertThrows(InsufficientQuantityException.class, () -> book.sell(d("11"), d("100"), NO_FEE));

        assertEquals("cannot sell 11: only 10 held (short by 1)", e.getMessage());
        assertEquals(d("11"), e.requested());
        assertEquals(d("10"), e.held());
        assertEquals(d("1"), e.shortBy());
        assertEquals(before, book.lots());
    }

    @Test
    void theSmallestImaginableOversaleIsStillRefused() {
        LotBook book = new LotBook(USD);
        book.buy(d("10"), d("100"), NO_FEE);
        InsufficientQuantityException e = assertThrows(InsufficientQuantityException.class, () -> book.sell(d("10.000000001"), d("1"), NO_FEE));
        assertEquals("0.000000001", e.shortBy().toPlainString());
        assertEquals(List.of(lot("10", 100_000)), book.lots());
    }

    @Test
    void sellingFromAnEmptyBookIsRefused() {
        LotBook book = new LotBook(USD);
        InsufficientQuantityException e = assertThrows(InsufficientQuantityException.class, () -> book.sell(d("1"), d("1"), NO_FEE));
        assertEquals("cannot sell 1: only 0 held (short by 1)", e.getMessage());
    }

    @Test
    void afterARefusedSaleTheBookStillWorks() {
        LotBook book = new LotBook(USD);
        book.buy(d("10"), d("100"), NO_FEE);
        assertThrows(InsufficientQuantityException.class, () -> book.sell(d("11"), d("100"), NO_FEE));
        assertEquals(usd(50_000), book.sell(d("5"), d("200"), NO_FEE).realized());
    }

    // ------------------------------------------------------------------ nothing is left half-done

    @Test
    void anArithmeticFailureInTheMiddleOfASaleLeavesTheBookAsItWas() {
        LotBook book = new LotBook(USD);
        Money max = Money.ofMinor(Long.MAX_VALUE, USD);
        book.buy(d("1"), new BigDecimal("92233720368547758.07"), NO_FEE);
        book.buy(d("1"), new BigDecimal("92233720368547758.07"), NO_FEE);
        assertEquals(List.of(new Lot(d("1"), max), new Lot(d("1"), max)), book.lots());
        List<Lot> before = book.lots();

        // Two lots that each cost the most a long can hold cannot be summed as the cost of one sale.
        assertThrows(ArithmeticException.class, () -> book.sell(d("2"), d("1"), NO_FEE));

        assertEquals(before, book.lots());
    }

    @Test
    void aProfitThatCannotBeRepresentedIsRefusedBeforeTheBookChanges() {
        LotBook book = new LotBook(USD);
        book.buy(d("1"), new BigDecimal("92233720368547758.07"), NO_FEE);
        List<Lot> before = book.lots();

        // proceeds 0 - fee (the most possible) - cost (the most possible) cannot be held in a long
        assertThrows(ArithmeticException.class, () -> book.sell(d("1"), d("0"), Money.ofMinor(Long.MAX_VALUE, USD)));

        assertEquals(before, book.lots());
    }

    @Test
    void proceedsThatDoNotFitAreRefusedAndChangeNothing() {
        LotBook book = new LotBook(USD);
        book.buy(d("1"), d("1"), NO_FEE);
        assertThrows(ArithmeticException.class, () -> book.sell(d("1"), new BigDecimal("1E+30"), NO_FEE));
        assertEquals(List.of(lot("1", 100)), book.lots());
    }

    @Test
    void aPurchaseThatDoesNotFitIsRefusedAndChangesNothing() {
        LotBook book = new LotBook(USD);
        book.buy(d("1"), d("1"), NO_FEE);
        assertThrows(ArithmeticException.class, () -> book.buy(d("2"), new BigDecimal("92233720368547758.07"), NO_FEE));
        assertThrows(ArithmeticException.class, () -> book.buy(d("1"), new BigDecimal("92233720368547758.07"), usd(1)));
        assertEquals(List.of(lot("1", 100)), book.lots());
    }

    @Test
    void theSnapshotReturnedByLotsIsNotAffectedByLaterTrades() {
        LotBook book = new LotBook(USD);
        book.buy(d("10"), d("10"), NO_FEE);
        List<Lot> snapshot = book.lots();
        book.sell(d("4"), d("10"), NO_FEE);
        book.buy(d("1"), d("10"), NO_FEE);
        assertEquals(List.of(lot("10", 10_000)), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(lot("1", 1)));
    }

    // ------------------------------------------------------------------ bad arguments

    @Test
    void quantitiesPricesAndFeesMustBeSensible() {
        LotBook book = new LotBook(USD);
        book.buy(d("10"), d("10"), NO_FEE);
        assertThrows(IllegalArgumentException.class, () -> book.buy(d("0"), d("1"), NO_FEE));
        assertThrows(IllegalArgumentException.class, () -> book.buy(d("0.000"), d("1"), NO_FEE));
        assertThrows(IllegalArgumentException.class, () -> book.buy(d("-1"), d("1"), NO_FEE));
        assertThrows(IllegalArgumentException.class, () -> book.buy(d("1"), d("-0.01"), NO_FEE));
        assertThrows(IllegalArgumentException.class, () -> book.buy(d("1"), d("1"), usd(-1)));
        assertEquals("fee is in JPY but the book is in USD",
            assertThrows(IllegalArgumentException.class, () -> book.buy(d("1"), d("1"), Money.zero(JPY))).getMessage());
        assertThrows(IllegalArgumentException.class, () -> book.sell(d("0"), d("1"), NO_FEE));
        assertThrows(IllegalArgumentException.class, () -> book.sell(d("-1"), d("1"), NO_FEE));
        assertThrows(IllegalArgumentException.class, () -> book.sell(d("1"), d("-1"), NO_FEE));
        assertThrows(IllegalArgumentException.class, () -> book.sell(d("1"), d("1"), usd(-1)));
        assertEquals("fee is in JPY but the book is in USD",
            assertThrows(IllegalArgumentException.class, () -> book.sell(d("1"), d("1"), Money.zero(JPY))).getMessage());
        assertThrows(NullPointerException.class, () -> book.buy(null, d("1"), NO_FEE));
        assertThrows(NullPointerException.class, () -> book.buy(d("1"), null, NO_FEE));
        assertThrows(NullPointerException.class, () -> book.buy(d("1"), d("1"), null));
        assertThrows(NullPointerException.class, () -> book.sell(null, d("1"), NO_FEE));
        assertThrows(NullPointerException.class, () -> book.sell(d("1"), null, NO_FEE));
        assertThrows(NullPointerException.class, () -> book.sell(d("1"), d("1"), null));
        assertEquals(List.of(lot("10", 10_000)), book.lots(), "none of the refused calls may have had an effect");
    }

    @Test
    void aBookNeedsACurrencyThatCanHoldAnAmount() {
        assertThrows(NullPointerException.class, () -> new LotBook(null));
        assertThrows(IllegalArgumentException.class, () -> new LotBook(Currency.getInstance("XXX")));
    }

    @Test
    void aLotMustHoldSomethingAndNeverHaveNegativeCost() {
        assertThrows(IllegalArgumentException.class, () -> new Lot(d("0"), usd(1)));
        assertThrows(IllegalArgumentException.class, () -> new Lot(d("-1"), usd(1)));
        assertThrows(IllegalArgumentException.class, () -> new Lot(d("1"), usd(-1)));
        assertEquals(lot("1", 0), new Lot(d("1.0"), usd(0)));
        assertTrue(new Lot(d("1"), usd(0)).cost().isZero());
    }
}
