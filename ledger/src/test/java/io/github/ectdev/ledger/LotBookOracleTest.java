package io.github.ectdev.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Currency;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Two independent checks of the lot book over random histories. Seeds are fixed and appear in every message.
 *
 * <p><b>The oracle.</b> Everything is whole units at whole-dollar prices with no fees, so no rounding can happen, and
 * the oracle can be written as differently as possible: it keeps one entry <em>per unit bought</em> in a queue, and a
 * sale simply removes units from the front and adds up what they cost. It shares no arithmetic with the lot book,
 * which divides a lot's cost when it is partly used.
 *
 * <p><b>The invariants.</b> With fractional quantities, fractional prices and fees, rounding does happen, and there
 * is no unit-by-unit answer. What must hold regardless is that nothing is created or lost: what was bought equals
 * what was sold plus what is still held, in cents and in quantity, after every single operation.
 */
class LotBookOracleTest {

    private static final Currency USD = MoneyTest.USD;
    private static final long[] SEEDS = {1, 2, 3, 5, 8, 13, 21, 34};

    @Test
    void matchesAUnitByUnitQueueWhenNoRoundingCanOccur() {
        int operations = 0;
        int refusals = 0;
        for (long seed : SEEDS) {
            Random random = new Random(seed);
            LotBook book = new LotBook(USD);
            Deque<long[]> units = new ArrayDeque<>();   // {lot number, cost of this unit in cents}
            int nextLot = 0;

            for (int step = 0; step < 400; step++) {
                String where = "seed " + seed + ", step " + step;
                boolean buy = units.isEmpty() || random.nextInt(100) < 55;
                if (buy) {
                    int quantity = 1 + random.nextInt(20);
                    int dollars = random.nextInt(500);   // includes a free lot
                    book.buy(BigDecimal.valueOf(quantity), BigDecimal.valueOf(dollars), Money.zero(USD));
                    for (int i = 0; i < quantity; i++) {
                        units.addLast(new long[] {nextLot, dollars * 100L});
                    }
                    nextLot++;
                } else if (random.nextInt(100) < 8) {
                    // Ask for more than exists: always refused, never changes anything.
                    int tooMany = units.size() + 1 + random.nextInt(5);
                    List<Lot> before = book.lots();
                    InsufficientQuantityException e = assertThrows(InsufficientQuantityException.class,
                        () -> book.sell(BigDecimal.valueOf(tooMany), BigDecimal.ONE, Money.zero(USD)), where);
                    assertEquals(BigDecimal.valueOf(tooMany - units.size()), e.shortBy(), where);
                    assertEquals(before, book.lots(), where);
                    refusals++;
                } else {
                    int quantity = 1 + random.nextInt(units.size());
                    int dollars = random.nextInt(600);
                    long cost = 0;
                    for (int i = 0; i < quantity; i++) {
                        cost += units.removeFirst()[1];
                    }
                    Sale sale = book.sell(BigDecimal.valueOf(quantity), BigDecimal.valueOf(dollars), Money.zero(USD));
                    assertEquals(cost, sale.costOfSold().minor(), "cost of sold, " + where);
                    assertEquals(quantity * dollars * 100L, sale.proceeds().minor(), "proceeds, " + where);
                    assertEquals(quantity * dollars * 100L - cost, sale.realized().minor(), "realized, " + where);
                    assertEquals(BigDecimal.valueOf(quantity), sale.quantity(), where);
                }

                // The book must now equal the queue, lot by lot.
                assertEquals(BigDecimal.valueOf(units.size()), book.quantity(), "quantity, " + where);
                assertEquals(units.stream().mapToLong(u -> u[1]).sum(), book.costBasis().minor(), "cost basis, " + where);
                assertEquals(groupIntoLots(units), book.lots(), "lots, " + where);
                operations++;
            }
        }
        assertEquals(SEEDS.length * 400, operations);
        assertTrue(refusals > 20, "the refusal branch must actually be exercised: " + refusals);
    }

    /** Consecutive units of the same lot become one lot again: their number and their total cost. */
    private static List<Lot> groupIntoLots(Deque<long[]> units) {
        List<Lot> lots = new ArrayList<>();
        long currentLot = -1;
        long count = 0;
        long cost = 0;
        for (long[] unit : units) {
            if (unit[0] != currentLot && count > 0) {
                lots.add(new Lot(BigDecimal.valueOf(count), Money.ofMinor(cost, USD)));
                count = 0;
                cost = 0;
            }
            currentLot = unit[0];
            count++;
            cost += unit[1];
        }
        if (count > 0) {
            lots.add(new Lot(BigDecimal.valueOf(count), Money.ofMinor(cost, USD)));
        }
        return lots;
    }

    @Test
    void nothingIsCreatedOrLostWhenRoundingDoesHappen() {
        int partialSales = 0;
        for (long seed : SEEDS) {
            Random random = new Random(seed * 7919);
            LotBook book = new LotBook(USD);
            long boughtCents = 0;
            long soldCostCents = 0;
            BigDecimal boughtQuantity = BigDecimal.ZERO;
            BigDecimal soldQuantity = BigDecimal.ZERO;

            for (int step = 0; step < 500; step++) {
                String where = "seed " + seed + ", step " + step;
                if (book.quantity().signum() == 0 || random.nextInt(100) < 55) {
                    BigDecimal quantity = BigDecimal.valueOf(1 + random.nextInt(100_000), random.nextInt(5));
                    BigDecimal price = BigDecimal.valueOf(random.nextInt(2_000_000), random.nextInt(5));
                    Money fee = Money.ofMinor(random.nextInt(3) == 0 ? random.nextInt(2_000) : 0, USD);
                    book.buy(quantity, price, fee);
                    boughtCents += Money.of(quantity.multiply(price), USD, java.math.RoundingMode.HALF_EVEN).plus(fee).minor();
                    boughtQuantity = boughtQuantity.add(quantity);
                } else {
                    List<Lot> before = book.lots();
                    BigDecimal held = book.quantity();
                    // A random fraction of what is held, sometimes all of it.
                    BigDecimal quantity = random.nextInt(8) == 0
                        ? held
                        : held.multiply(BigDecimal.valueOf(1 + random.nextInt(1_000), 3)).setScale(held.scale() + 3, java.math.RoundingMode.DOWN);
                    if (quantity.signum() == 0) {
                        continue;
                    }
                    BigDecimal price = BigDecimal.valueOf(random.nextInt(2_000_000), random.nextInt(5));
                    Money fee = Money.ofMinor(random.nextInt(3) == 0 ? random.nextInt(2_000) : 0, USD);
                    Sale sale = book.sell(quantity, price, fee);

                    soldCostCents += sale.costOfSold().minor();
                    soldQuantity = soldQuantity.add(quantity);
                    assertEquals(0, sale.quantity().compareTo(quantity), "consumed quantities add up to the sale, " + where);
                    assertEquals(sale.proceeds().minus(fee).minus(sale.costOfSold()), sale.realized(), where);
                    partialSales += checkRounding(before, sale, where);
                }

                // Conservation, after every operation.
                assertEquals(boughtCents, soldCostCents + book.costBasis().minor(), "cents bought = cents sold + cents held, " + where);
                assertEquals(0, boughtQuantity.subtract(soldQuantity).compareTo(book.quantity()), "quantity conservation, " + where);
                for (Lot lot : book.lots()) {
                    assertTrue(lot.quantity().signum() > 0 && !lot.cost().isNegative(), "lot sanity, " + where);
                }
            }
        }
        assertTrue(partialSales > 300, "the rounding path must actually be exercised: " + partialSales);
    }

    /**
     * Every part of a sale was taken from the lot at its position in the queue, in order; whole lots at exactly their
     * cost, and the one partly used lot, if any, at a share within half a cent of the exact proportion. Returns 1 if
     * there was a partly used lot.
     */
    private static int checkRounding(List<Lot> before, Sale sale, String where) {
        List<Sale.Consumption> parts = sale.consumed();
        for (int i = 0; i < parts.size(); i++) {
            Lot lot = before.get(i);
            Sale.Consumption part = parts.get(i);
            if (part.quantity().compareTo(lot.quantity()) == 0) {
                assertEquals(lot.cost(), part.cost(), "a whole lot is consumed at exactly its cost, " + where);
            } else {
                assertEquals(parts.size() - 1, i, "only the last lot can be partly used, " + where);
                assertTrue(part.quantity().compareTo(lot.quantity()) < 0, where);
                // |share - lotCost * used / lotQuantity| <= 1/2   <=>   2 * |share * lotQuantity - lotCost * used| <= lotQuantity
                BigDecimal lhs = BigDecimal.valueOf(part.cost().minor()).multiply(lot.quantity());
                BigDecimal rhs = BigDecimal.valueOf(lot.cost().minor()).multiply(part.quantity());
                BigDecimal twiceError = lhs.subtract(rhs).abs().multiply(BigDecimal.valueOf(2));
                assertTrue(twiceError.compareTo(lot.quantity()) <= 0, "rounding error above half a cent, " + where);
                BigInteger cost = BigInteger.valueOf(part.cost().minor());
                assertTrue(cost.signum() >= 0 && cost.compareTo(BigInteger.valueOf(lot.cost().minor())) <= 0, where);
                return 1;
            }
        }
        return 0;
    }
}
