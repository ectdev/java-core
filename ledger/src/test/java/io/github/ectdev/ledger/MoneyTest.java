package io.github.ectdev.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every expected value here was worked out by hand. The allocation tables show the arithmetic in comments so the
 * reasoning can be checked without running anything.
 */
class MoneyTest {

    static final Currency USD = Currency.getInstance("USD");
    static final Currency JPY = Currency.getInstance("JPY");
    static final Currency KWD = Currency.getInstance("KWD");
    static final Currency EUR = Currency.getInstance("EUR");

    static Money usd(long minor) {
        return Money.ofMinor(minor, USD);
    }

    static List<Long> minors(List<Money> shares) {
        return shares.stream().map(Money::minor).toList();
    }

    // ------------------------------------------------------------------ currencies and text

    @Test
    void theNumberOfDecimalsComesFromTheCurrency() {
        assertEquals(2, USD.getDefaultFractionDigits());
        assertEquals(0, JPY.getDefaultFractionDigits());
        assertEquals(3, KWD.getDefaultFractionDigits());
        assertEquals(new BigDecimal("12.30"), usd(1230).toMajor());
        assertEquals(new BigDecimal("1230"), Money.ofMinor(1230, JPY).toMajor());
        assertEquals(new BigDecimal("1.230"), Money.ofMinor(1230, KWD).toMajor());
    }

    @Test
    void aCurrencyWithoutAMinorUnitCannotHoldAnAmount() {
        Currency noCurrency = Currency.getInstance("XXX");
        assertThrows(IllegalArgumentException.class, () -> Money.ofMinor(1, noCurrency));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("1", noCurrency));
        assertThrows(IllegalArgumentException.class, () -> Money.of(BigDecimal.ONE, noCurrency, RoundingMode.HALF_EVEN));
    }

    static Stream<Arguments> texts() {
        return Stream.of(
            Arguments.of(usd(5), "0.05 USD"),
            Arguments.of(usd(-5), "-0.05 USD"),
            Arguments.of(usd(0), "0.00 USD"),
            Arguments.of(usd(123456), "1234.56 USD"),
            Arguments.of(usd(100), "1.00 USD"),
            Arguments.of(usd(Long.MAX_VALUE), "92233720368547758.07 USD"),
            Arguments.of(usd(Long.MIN_VALUE), "-92233720368547758.08 USD"),
            Arguments.of(Money.ofMinor(1500, JPY), "1500 JPY"),
            Arguments.of(Money.ofMinor(-1, JPY), "-1 JPY"),
            Arguments.of(Money.ofMinor(1, KWD), "0.001 KWD"),
            Arguments.of(Money.ofMinor(-1001, KWD), "-1.001 KWD"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("texts")
    void toStringShowsPlainDigitsAndTheIsoCode(Money money, String expected) {
        assertEquals(expected, money.toString());
    }

    static Stream<Arguments> parsed() {
        return Stream.of(
            Arguments.of("1234.5", USD, 123450L),
            Arguments.of("0.05", USD, 5L),
            Arguments.of("-0.05", USD, -5L),
            Arguments.of("0", USD, 0L),
            Arguments.of("-0", USD, 0L),
            Arguments.of("-0.00", USD, 0L),
            Arguments.of("007", USD, 700L),
            Arguments.of("1.500", USD, 150L),          // a trailing zero beyond the currency's decimals loses nothing
            Arguments.of("1.50000000000", USD, 150L),
            Arguments.of("100", JPY, 100L),
            Arguments.of("100.0", JPY, 100L),
            Arguments.of("1.001", KWD, 1001L),
            Arguments.of("92233720368547758.07", USD, Long.MAX_VALUE),
            Arguments.of("-92233720368547758.08", USD, Long.MIN_VALUE));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("parsed")
    void parseReadsPlainDecimalAmounts(String text, Currency currency, long minor) {
        assertEquals(Money.ofMinor(minor, currency), Money.parse(text, currency));
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"", " ", "1,234.50", " 1", "1 ", "1e3", "1E3", "+1", "1.", ".5", "-", "--1", "1.2.3", "abc",
        "1 000", "١٢", "1٫50", "½", "0x10", "NaN", "Infinity", "$1.00"})
    void parseRefusesAnythingThatIsNotAPlainAmount(String text) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Money.parse(text, USD));
        assertTrue(e.getMessage().contains("not an amount"), e.getMessage());
    }

    @Test
    void parseRefusesToDropInformation() {
        IllegalArgumentException cents = assertThrows(IllegalArgumentException.class, () -> Money.parse("1.005", USD));
        assertEquals("1.005 has more decimal places than USD allows (2)", cents.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Money.parse("1.5", JPY));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("0.0001", KWD));
    }

    @Test
    void parseRefusesWhatDoesNotFit() {
        // Long.MAX_VALUE cents is 92233720368547758.07; one cent more is not representable.
        IllegalArgumentException tooBig = assertThrows(IllegalArgumentException.class, () -> Money.parse("92233720368547758.08", USD));
        assertEquals("92233720368547758.08 USD is too large", tooBig.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Money.parse("-92233720368547758.09", USD));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("9".repeat(40), USD));
    }

    @Test
    void parseAndToStringAreInverse() {
        Random random = new Random(11);
        for (int i = 0; i < 2000; i++) {
            Money money = Money.ofMinor(random.nextLong() >> random.nextInt(63), i % 3 == 0 ? JPY : i % 3 == 1 ? USD : KWD);
            String text = money.toString();
            String number = text.substring(0, text.indexOf(' '));
            assertEquals(money, Money.parse(number, money.currency()), text);
        }
    }

    // ------------------------------------------------------------------ rounding from major units

    static Stream<Arguments> rounding() {
        BigDecimal eighth = new BigDecimal("0.125");       // 12.5 cents
        BigDecimal fifteenSixteenths = new BigDecimal("0.135"); // 13.5 cents
        BigDecimal negEighth = new BigDecimal("-0.125");   // -12.5 cents
        return Stream.of(
            Arguments.of(eighth, RoundingMode.HALF_EVEN, 12L),
            Arguments.of(eighth, RoundingMode.HALF_UP, 13L),
            Arguments.of(eighth, RoundingMode.HALF_DOWN, 12L),
            Arguments.of(eighth, RoundingMode.DOWN, 12L),
            Arguments.of(eighth, RoundingMode.UP, 13L),
            Arguments.of(eighth, RoundingMode.FLOOR, 12L),
            Arguments.of(eighth, RoundingMode.CEILING, 13L),
            Arguments.of(fifteenSixteenths, RoundingMode.HALF_EVEN, 14L),
            Arguments.of(negEighth, RoundingMode.HALF_EVEN, -12L),
            Arguments.of(negEighth, RoundingMode.HALF_UP, -13L),
            Arguments.of(negEighth, RoundingMode.HALF_DOWN, -12L),
            Arguments.of(negEighth, RoundingMode.FLOOR, -13L),
            Arguments.of(negEighth, RoundingMode.CEILING, -12L),
            Arguments.of(new BigDecimal("0.1249"), RoundingMode.HALF_EVEN, 12L),
            Arguments.of(new BigDecimal("0.1251"), RoundingMode.HALF_EVEN, 13L),
            Arguments.of(new BigDecimal("2"), RoundingMode.UNNECESSARY, 200L),
            Arguments.of(new BigDecimal("1E+2"), RoundingMode.UNNECESSARY, 10000L));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("rounding")
    void ofRoundsMajorUnitsToTheMinorUnitAsTold(BigDecimal major, RoundingMode mode, long expected) {
        assertEquals(usd(expected), Money.of(major, USD, mode));
    }

    @Test
    void ofWithUnnecessaryRefusesToRound() {
        assertThrows(ArithmeticException.class, () -> Money.of(new BigDecimal("0.125"), USD, RoundingMode.UNNECESSARY));
    }

    @Test
    void ofRefusesWhatDoesNotFitInALong() {
        assertThrows(ArithmeticException.class, () -> Money.of(new BigDecimal("92233720368547758.08"), USD, RoundingMode.HALF_EVEN));
        assertEquals(usd(Long.MAX_VALUE), Money.of(new BigDecimal("92233720368547758.07"), USD, RoundingMode.HALF_EVEN));
        assertEquals(usd(Long.MIN_VALUE), Money.of(new BigDecimal("-92233720368547758.08"), USD, RoundingMode.HALF_EVEN));
    }

    // ------------------------------------------------------------------ arithmetic

    @Test
    void plusMinusNegateAbsAndTimes() {
        assertEquals(usd(350), usd(100).plus(usd(250)));
        assertEquals(usd(-150), usd(100).minus(usd(250)));
        assertEquals(usd(-100), usd(100).negate());
        assertEquals(usd(100), usd(-100).abs());
        assertEquals(usd(100), usd(100).abs());
        assertEquals(usd(-300), usd(100).times(-3));
        assertEquals(usd(0), usd(100).times(0));
    }

    @Test
    void signAndZeroTests() {
        assertEquals(1, usd(5).signum());
        assertEquals(-1, usd(-5).signum());
        assertEquals(0, usd(0).signum());
        assertTrue(usd(0).isZero());
        assertTrue(!usd(5).isZero());
        assertTrue(usd(-1).isNegative());
        assertTrue(!usd(0).isNegative());
        assertTrue(!usd(1).isNegative());
    }

    @Test
    void overflowThrowsInsteadOfWrappingAround() {
        assertThrows(ArithmeticException.class, () -> usd(Long.MAX_VALUE).plus(usd(1)));
        assertThrows(ArithmeticException.class, () -> usd(Long.MIN_VALUE).minus(usd(1)));
        assertThrows(ArithmeticException.class, () -> usd(Long.MIN_VALUE).negate());
        assertThrows(ArithmeticException.class, () -> usd(Long.MIN_VALUE).abs());
        assertThrows(ArithmeticException.class, () -> usd(Long.MAX_VALUE).times(2));
        assertEquals(usd(Long.MAX_VALUE), usd(Long.MAX_VALUE - 1).plus(usd(1)));
    }

    @Test
    void differentCurrenciesNeverMix() {
        Money euro = Money.ofMinor(100, EUR);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> usd(100).plus(euro));
        assertEquals("currencies differ: USD and EUR", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> usd(100).minus(euro));
        assertThrows(IllegalArgumentException.class, () -> usd(100).compareTo(euro));
        assertNotEquals(usd(100), euro);
    }

    @Test
    void ordering() {
        assertTrue(usd(1).compareTo(usd(2)) < 0);
        assertTrue(usd(2).compareTo(usd(1)) > 0);
        assertEquals(0, usd(2).compareTo(usd(2)));
        assertTrue(usd(-5).compareTo(usd(3)) < 0);
        assertTrue(usd(Long.MIN_VALUE).compareTo(usd(Long.MAX_VALUE)) < 0, "a subtraction-based compare would overflow");
    }

    static Stream<Arguments> multiplications() {
        BigDecimal oneAndAHalf = new BigDecimal("1.5");
        return Stream.of(
            // 5 minor units x 1.5 = 7.5
            Arguments.of(5L, oneAndAHalf, RoundingMode.HALF_EVEN, 8L),
            Arguments.of(5L, oneAndAHalf, RoundingMode.HALF_UP, 8L),
            Arguments.of(5L, oneAndAHalf, RoundingMode.HALF_DOWN, 7L),
            Arguments.of(5L, oneAndAHalf, RoundingMode.FLOOR, 7L),
            Arguments.of(5L, oneAndAHalf, RoundingMode.CEILING, 8L),
            Arguments.of(5L, oneAndAHalf, RoundingMode.DOWN, 7L),
            Arguments.of(5L, oneAndAHalf, RoundingMode.UP, 8L),
            // -5 x 1.5 = -7.5
            Arguments.of(-5L, oneAndAHalf, RoundingMode.HALF_EVEN, -8L),
            Arguments.of(-5L, oneAndAHalf, RoundingMode.HALF_UP, -8L),
            Arguments.of(-5L, oneAndAHalf, RoundingMode.HALF_DOWN, -7L),
            Arguments.of(-5L, oneAndAHalf, RoundingMode.FLOOR, -8L),
            Arguments.of(-5L, oneAndAHalf, RoundingMode.CEILING, -7L),
            // 7 x 0.5 = 3.5 : half-even goes to 4, 5 x 0.5 = 2.5 : half-even goes to 2
            Arguments.of(7L, new BigDecimal("0.5"), RoundingMode.HALF_EVEN, 4L),
            Arguments.of(5L, new BigDecimal("0.5"), RoundingMode.HALF_EVEN, 2L),
            // a 20% tax on 12.34 USD: 1234 x 0.2 = 246.8
            Arguments.of(1234L, new BigDecimal("0.2"), RoundingMode.HALF_EVEN, 247L),
            Arguments.of(1234L, new BigDecimal("0.2"), RoundingMode.FLOOR, 246L),
            Arguments.of(1234L, BigDecimal.ZERO, RoundingMode.HALF_EVEN, 0L),
            Arguments.of(1234L, new BigDecimal("-1"), RoundingMode.HALF_EVEN, -1234L));
    }

    @ParameterizedTest(name = "{0} x {1} {2}")
    @MethodSource("multiplications")
    void multiplyRoundsAsTold(long minor, BigDecimal factor, RoundingMode mode, long expected) {
        assertEquals(usd(expected), usd(minor).multiply(factor, mode));
    }

    @Test
    void multiplyOverflowThrows() {
        assertThrows(ArithmeticException.class, () -> usd(Long.MAX_VALUE).multiply(new BigDecimal("2"), RoundingMode.HALF_EVEN));
        assertThrows(ArithmeticException.class, () -> usd(1).multiply(new BigDecimal("1E+30"), RoundingMode.HALF_EVEN));
        assertThrows(ArithmeticException.class, () -> usd(1).multiply(new BigDecimal("0.5"), RoundingMode.UNNECESSARY));
    }

    // ------------------------------------------------------------------ allocate: hand-worked tables

    static Stream<Arguments> allocations() {
        return Stream.of(
            // 100 / 3 = 33.33..: floors 33,33,33 leave 1, and the earliest takes it
            Arguments.of(100L, new long[] {1, 1, 1}, List.of(34L, 33L, 33L)),
            // 5 split 70:30 is 3.5 and 1.5; floors 3 and 1 leave 1; the fractions are equal (.5 and .5), the earlier wins
            Arguments.of(5L, new long[] {70, 30}, List.of(4L, 1L)),
            // 5 split 30:70 is 1.5 and 3.5: a tie again, so the first gets it: 2 and 3
            Arguments.of(5L, new long[] {30, 70}, List.of(2L, 3L)),
            // 10 split 1:2:3:4 is exactly 1, 2, 3, 4
            Arguments.of(10L, new long[] {1, 2, 3, 4}, List.of(1L, 2L, 3L, 4L)),
            // 1 split in two: floors 0 and 0, one unit left, the first gets it
            Arguments.of(1L, new long[] {1, 1}, List.of(1L, 0L)),
            // 7 split four ways is 1.75 each: floors 1,1,1,1 leave 3, which go to the first three
            Arguments.of(7L, new long[] {1, 1, 1, 1}, List.of(2L, 2L, 2L, 1L)),
            // a zero weight gets nothing
            Arguments.of(10L, new long[] {0, 5, 5}, List.of(0L, 5L, 5L)),
            Arguments.of(10L, new long[] {5, 0, 5}, List.of(5L, 0L, 5L)),
            Arguments.of(1L, new long[] {0, 1}, List.of(0L, 1L)),
            // 10 split 1:1:1 is 3.33..: floors 3,3,3 leave 1
            Arguments.of(10L, new long[] {1, 1, 1}, List.of(4L, 3L, 3L)),
            // 10 split 2:3 is exactly 4 and 6
            Arguments.of(10L, new long[] {2, 3}, List.of(4L, 6L)),
            // 11 split 1:2:3 is 1.83, 3.67, 5.5: floors 1,3,5 leave 2; fractions .83,.67,.5 -> first two
            Arguments.of(11L, new long[] {1, 2, 3}, List.of(2L, 4L, 5L)),
            // the largest fraction wins even when it is not first: 5 split 1:1:3 is 1,1,3 exactly... use 7: 1.4,1.4,4.2
            // floors 1,1,4 leave 1; fractions .4,.4,.2 -> tie between the first two, the first wins
            Arguments.of(7L, new long[] {1, 1, 3}, List.of(2L, 1L, 4L)),
            // 8 split 1:1:3 is 1.6, 1.6, 4.8: floors 1,1,4 leave 2; fractions .6,.6,.8 -> third, then first
            Arguments.of(8L, new long[] {1, 1, 3}, List.of(2L, 1L, 5L)),
            // a single party gets everything
            Arguments.of(42L, new long[] {7}, List.of(42L)),
            // the weights only matter as ratios
            Arguments.of(10L, new long[] {200, 300}, List.of(4L, 6L)),
            // zero in, zeros out
            Arguments.of(0L, new long[] {1, 2, 3}, List.of(0L, 0L, 0L)),
            // a negative total mirrors the positive one
            Arguments.of(-5L, new long[] {70, 30}, List.of(-4L, -1L)),
            Arguments.of(-100L, new long[] {1, 1, 1}, List.of(-34L, -33L, -33L)),
            // the extremes of a long
            Arguments.of(Long.MAX_VALUE, new long[] {1, 1}, List.of(4611686018427387904L, 4611686018427387903L)),
            Arguments.of(Long.MIN_VALUE, new long[] {1, 1}, List.of(-4611686018427387904L, -4611686018427387904L)),
            Arguments.of(Long.MAX_VALUE, new long[] {Long.MAX_VALUE, Long.MAX_VALUE}, List.of(4611686018427387904L, 4611686018427387903L)),
            Arguments.of(3L, new long[] {Long.MAX_VALUE, Long.MAX_VALUE}, List.of(2L, 1L)),
            Arguments.of(Long.MAX_VALUE, new long[] {Long.MAX_VALUE, 1}, List.of(9223372036854775806L, 1L)));
    }

    @ParameterizedTest
    @MethodSource("allocations")
    void allocateMatchesTheHandWorkedTables(long total, long[] weights, List<Long> expected) {
        assertEquals(expected, minors(usd(total).allocate(weights)));
    }

    @Test
    void splitDividesAsEvenlyAsWholeUnitsAllow() {
        assertEquals(List.of(34L, 33L, 33L), minors(usd(100).split(3)));
        assertEquals(List.of(100L), minors(usd(100).split(1)));
        assertEquals(List.of(1L, 1L, 0L, 0L), minors(usd(2).split(4)));
        assertEquals("parts must be at least 1: 0", assertThrows(IllegalArgumentException.class, () -> usd(100).split(0)).getMessage());
        assertEquals("parts must be at least 1: -3", assertThrows(IllegalArgumentException.class, () -> usd(100).split(-3)).getMessage());
    }

    @Test
    void allocateKeepsTheCurrencyOfTheAmount() {
        List<Money> shares = Money.ofMinor(1000, JPY).allocate(1, 1, 1);
        assertEquals(List.of(Money.ofMinor(334, JPY), Money.ofMinor(333, JPY), Money.ofMinor(333, JPY)), shares);
    }

    @Test
    void allocateRefusesUnusableWeights() {
        assertEquals("allocate needs at least one weight",
            assertThrows(IllegalArgumentException.class, () -> usd(100).allocate()).getMessage());
        assertEquals("weights must not all be zero",
            assertThrows(IllegalArgumentException.class, () -> usd(100).allocate(0, 0)).getMessage());
        assertEquals("weights must not be negative: -1",
            assertThrows(IllegalArgumentException.class, () -> usd(100).allocate(1, -1)).getMessage());
        assertEquals("weights must not be negative: -1",
            assertThrows(IllegalArgumentException.class, () -> usd(100).allocate(-1, 5)).getMessage());
        assertThrows(NullPointerException.class, () -> usd(100).allocate((long[]) null));
    }

    @Test
    void theNaiveRoundingLosesACentAndAllocateDoesNot() {
        // The motivating example: 1.00 split three ways by rounding each share.
        BigDecimal third = new BigDecimal("100").divide(new BigDecimal("3"), 0, RoundingMode.HALF_EVEN);
        assertEquals(99, third.longValueExact() * 3, "three rounded thirds make 0.99");
        long sum = usd(100).split(3).stream().mapToLong(Money::minor).sum();
        assertEquals(100, sum);
    }

    // ------------------------------------------------------------------ allocate: properties on random input

    @Test
    void allocatePropertiesHoldForRandomTotalsAndWeights() {
        for (long seed : new long[] {1, 2, 3, 99, 2024}) {
            Random random = new Random(seed);
            for (int round = 0; round < 1500; round++) {
                long total = switch (random.nextInt(5)) {
                    case 0 -> random.nextInt(50) - 25;
                    case 1 -> random.nextLong();
                    case 2 -> random.nextInt(1_000_000) - 500_000;
                    case 3 -> random.nextBoolean() ? Long.MAX_VALUE - random.nextInt(3) : Long.MIN_VALUE + random.nextInt(3);
                    default -> random.nextInt(2000) - 1000;
                };
                int parties = 1 + random.nextInt(8);
                long[] weights = new long[parties];
                boolean anyPositive = false;
                for (int i = 0; i < parties; i++) {
                    weights[i] = switch (random.nextInt(4)) {
                        case 0 -> 0;
                        case 1 -> random.nextInt(5);
                        case 2 -> random.nextInt(1000);
                        default -> random.nextLong() & Long.MAX_VALUE;
                    };
                    anyPositive |= weights[i] > 0;
                }
                if (!anyPositive) {
                    weights[random.nextInt(parties)] = 1 + random.nextInt(10);
                }
                String where = "seed " + seed + " round " + round + " total " + total + " weights " + List.of(weights);
                List<Long> shares = minors(usd(total).allocate(weights));
                check(total, weights, shares, where);
            }
        }
    }

    private static void check(long total, long[] weights, List<Long> shares, String where) {
        assertEquals(weights.length, shares.size(), where);

        BigInteger sum = BigInteger.ZERO;
        BigInteger weightSum = BigInteger.ZERO;
        for (int i = 0; i < weights.length; i++) {
            sum = sum.add(BigInteger.valueOf(shares.get(i)));
            weightSum = weightSum.add(BigInteger.valueOf(weights[i]));
        }
        assertEquals(BigInteger.valueOf(total), sum, "the shares must add up to the total: " + where);

        for (int i = 0; i < weights.length; i++) {
            long share = shares.get(i);
            // No share is further than one unit from the exact proportion: |share * W - total * w| < W.
            BigInteger error = BigInteger.valueOf(share).multiply(weightSum)
                .subtract(BigInteger.valueOf(total).multiply(BigInteger.valueOf(weights[i]))).abs();
            assertTrue(error.compareTo(weightSum) < 0, "share " + i + " is off by a unit or more: " + where);
            // No share has the wrong sign, and a zero weight gets nothing.
            assertTrue(total >= 0 ? share >= 0 : share <= 0, "sign of share " + i + ": " + where);
            if (weights[i] == 0) {
                assertEquals(0, share, "zero weight " + i + ": " + where);
            }
            for (int j = 0; j < weights.length; j++) {
                if (weights[i] > weights[j]) {
                    // A larger weight never gets a smaller share (in magnitude).
                    assertTrue(BigInteger.valueOf(share).abs().compareTo(BigInteger.valueOf(shares.get(j)).abs()) >= 0,
                        "monotone in weight, " + i + " vs " + j + ": " + where);
                }
            }
        }
    }

    @Test
    void allocatingTheNegatedTotalGivesTheNegatedShares() {
        Random random = new Random(5);
        for (int round = 0; round < 2000; round++) {
            long total = random.nextLong() >> random.nextInt(60);
            if (total == Long.MIN_VALUE) {
                continue;
            }
            long[] weights = new long[1 + random.nextInt(6)];
            for (int i = 0; i < weights.length; i++) {
                weights[i] = random.nextInt(100);
            }
            weights[0] += 1;
            List<Long> positive = minors(usd(Math.abs(total)).allocate(weights));
            List<Long> signed = minors(usd(total).allocate(weights));
            List<Long> expected = new ArrayList<>();
            for (long share : positive) {
                expected.add(total < 0 ? -share : share);
            }
            assertEquals(expected, signed, "total " + total);
        }
    }

    @Test
    void equalWeightsGiveSharesThatDifferByAtMostOneWithTheLargerOnesFirst() {
        for (int parts = 1; parts <= 12; parts++) {
            for (long total : new long[] {0, 1, 2, 7, 99, 100, 101, 1000, -7, -100}) {
                List<Long> shares = minors(usd(total).split(parts));
                long max = shares.stream().mapToLong(Math::abs).max().orElseThrow();
                long min = shares.stream().mapToLong(Math::abs).min().orElseThrow();
                assertTrue(max - min <= 1, parts + " ways of " + total + ": " + shares);
                for (int i = 1; i < shares.size(); i++) {
                    assertTrue(Math.abs(shares.get(i - 1)) >= Math.abs(shares.get(i)), "larger first: " + shares);
                }
            }
        }
    }
}
