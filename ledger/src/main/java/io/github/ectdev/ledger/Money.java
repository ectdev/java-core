package io.github.ectdev.ledger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An amount of money, held as a whole number of the currency's smallest unit (cents for USD, yen for JPY, fils
 * for KWD).
 *
 * <p>Why integers: a {@code double} cannot hold 0.10, and the error is visible after a few additions. A
 * {@link BigDecimal} can, but then every sum carries a scale that has to be kept in step with the currency, and an
 * amount like 0.125 USD can exist even though no such coin does. A {@code long} count of minor units makes both
 * problems disappear: an amount is always a real amount, addition is exact, and equality is the obvious one.
 *
 * <p>Arithmetic that could overflow throws {@link ArithmeticException} instead of wrapping around. Mixing
 * currencies throws {@link IllegalArgumentException}; there is no implicit conversion because there is no single
 * right rate.
 *
 * <p>Wherever a result cannot be exact (a percentage, a share of a total) the caller states how to round, or, for
 * dividing a total among parties, uses {@link #allocate}, which never loses or invents a unit.
 */
public record Money(long minor, Currency currency) implements Comparable<Money> {

    private static final Pattern AMOUNT = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException(
                "currency has no minor unit and cannot hold an amount: " + currency.getCurrencyCode());
        }
    }

    public static Money ofMinor(long minor, Currency currency) {
        return new Money(minor, currency);
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    /**
     * An amount given in major units (dollars, not cents), rounded to the currency's minor unit.
     *
     * @throws ArithmeticException if the result does not fit in a {@code long} of minor units, or if {@code mode}
     *                             is {@link RoundingMode#UNNECESSARY} and rounding would be needed
     */
    public static Money of(BigDecimal major, Currency currency, RoundingMode mode) {
        Objects.requireNonNull(major, "major");
        Objects.requireNonNull(mode, "mode");
        int digits = digits(currency);
        try {
            return new Money(major.setScale(digits, mode).unscaledValue().longValueExact(), currency);
        } catch (ArithmeticException e) {
            throw new ArithmeticException("cannot represent " + major.toPlainString() + " " + currency.getCurrencyCode()
                + " in minor units: " + e.getMessage());
        }
    }

    /**
     * Reads an amount such as {@code 1234.50} or {@code -0.05}: an optional minus sign, ASCII digits, and an optional
     * decimal point with digits. No grouping, no plus sign, no exponent, no spaces. More decimals than the currency
     * has are accepted only if they are all zeros ({@code 1.500} is 1.50 USD, {@code 1.005} is refused), because
     * parsing must not silently drop information.
     *
     * @throws IllegalArgumentException if the text is not an amount of this currency or is too large
     */
    public static Money parse(String text, Currency currency) {
        Objects.requireNonNull(text, "text");
        int digits = digits(currency);
        if (!AMOUNT.matcher(text).matches()) {
            throw new IllegalArgumentException("not an amount: \"" + text + "\"");
        }
        BigDecimal exact;
        try {
            exact = new BigDecimal(text).setScale(digits, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                text + " has more decimal places than " + currency.getCurrencyCode() + " allows (" + digits + ")");
        }
        try {
            return new Money(exact.unscaledValue().longValueExact(), currency);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(text + " " + currency.getCurrencyCode() + " is too large");
        }
    }

    private static int digits(Currency currency) {
        Objects.requireNonNull(currency, "currency");
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0) {
            throw new IllegalArgumentException(
                "currency has no minor unit and cannot hold an amount: " + currency.getCurrencyCode());
        }
        return digits;
    }

    /** The amount in major units with exactly the currency's number of decimals, for example {@code 12.30}. */
    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minor, currency.getDefaultFractionDigits());
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minor), currency);
    }

    public Money abs() {
        return minor < 0 ? negate() : this;
    }

    public Money times(long factor) {
        return new Money(Math.multiplyExact(minor, factor), currency);
    }

    /**
     * This amount times a fraction, such as a tax rate or a price ratio, rounded to a whole minor unit by {@code mode}.
     * The rounding mode is a required argument because there is no neutral default: half-up favours the receiver,
     * half-even is unbiased over many amounts, and the choice is usually a legal or contractual one.
     */
    public Money multiply(BigDecimal factor, RoundingMode mode) {
        Objects.requireNonNull(factor, "factor");
        Objects.requireNonNull(mode, "mode");
        try {
            return new Money(BigDecimal.valueOf(minor).multiply(factor).setScale(0, mode).longValueExact(), currency);
        } catch (ArithmeticException e) {
            throw new ArithmeticException("cannot multiply " + this + " by " + factor.toPlainString() + ": " + e.getMessage());
        }
    }

    public int signum() {
        return Long.signum(minor);
    }

    public boolean isZero() {
        return minor == 0;
    }

    public boolean isNegative() {
        return minor < 0;
    }

    /**
     * Divides this amount among parties in proportion to {@code weights}, so that the shares add up to exactly this
     * amount.
     *
     * <p>The problem is that 1.00 split three ways is 0.333…, and no coin is a third of a cent. Rounding each share
     * gives 0.33 + 0.33 + 0.33 = 0.99: a cent has vanished. This method uses the largest remainder method: every
     * share is first rounded <em>down</em> to a whole unit, which leaves a few units over (fewer than there are
     * parties), and these go one each to the parties whose exact share had the largest fraction left over. Ties go to
     * the earlier party, so the result is deterministic. The properties that follow, all tested: the shares add up to
     * the total; each differs from the exact proportion by less than one unit; a larger weight never gets less than a
     * smaller one; a weight of zero gets nothing; and the shares of a negative total are the negated shares of its
     * absolute value.
     *
     * @param weights relative weights, none negative and at least one positive; only their ratios matter
     * @return one share per weight, in the same order
     * @throws IllegalArgumentException if there are no weights, one is negative, or all are zero
     */
    public List<Money> allocate(long... weights) {
        Objects.requireNonNull(weights, "weights");
        if (weights.length == 0) {
            throw new IllegalArgumentException("allocate needs at least one weight");
        }
        BigInteger sum = BigInteger.ZERO;
        for (long weight : weights) {
            if (weight < 0) {
                throw new IllegalArgumentException("weights must not be negative: " + weight);
            }
            sum = sum.add(BigInteger.valueOf(weight));
        }
        if (sum.signum() == 0) {
            throw new IllegalArgumentException("weights must not all be zero");
        }

        // BigInteger throughout: total * weight can reach 2^126, far beyond a long.
        BigInteger total = BigInteger.valueOf(minor).abs();
        BigInteger[] shares = new BigInteger[weights.length];
        BigInteger[] remainders = new BigInteger[weights.length];
        BigInteger given = BigInteger.ZERO;
        for (int i = 0; i < weights.length; i++) {
            BigInteger[] quotientAndRemainder = total.multiply(BigInteger.valueOf(weights[i])).divideAndRemainder(sum);
            shares[i] = quotientAndRemainder[0];
            remainders[i] = quotientAndRemainder[1];
            given = given.add(shares[i]);
        }

        // The sum of the remainders is a multiple of 'sum', and each is smaller than it, so fewer than
        // weights.length units are left, and a zero weight (remainder 0) is never among the first of them.
        int leftover = total.subtract(given).intValueExact();
        List<Integer> byRemainder = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) {
            byRemainder.add(i);
        }
        byRemainder.sort((a, b) -> {
            int byFraction = remainders[b].compareTo(remainders[a]);
            return byFraction != 0 ? byFraction : Integer.compare(a, b);
        });
        for (int k = 0; k < leftover; k++) {
            int winner = byRemainder.get(k);
            shares[winner] = shares[winner].add(BigInteger.ONE);
        }

        List<Money> result = new ArrayList<>(weights.length);
        for (BigInteger share : shares) {
            result.add(new Money((minor < 0 ? share.negate() : share).longValueExact(), currency));
        }
        return List.copyOf(result);
    }

    /** Splits into {@code parts} shares as equal as whole units allow; the larger shares come first. */
    public List<Money> split(int parts) {
        if (parts < 1) {
            throw new IllegalArgumentException("parts must be at least 1: " + parts);
        }
        long[] equal = new long[parts];
        Arrays.fill(equal, 1L);
        return allocate(equal);
    }

    /** Compares amounts of the same currency; amounts of different currencies are not ordered. */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minor, other.minor);
    }

    /** For example {@code 1234.50 USD}, {@code -0.05 USD}, {@code 1500 JPY}: plain digits, a point, the ISO code. */
    @Override
    public String toString() {
        return toMajor().toPlainString() + " " + currency.getCurrencyCode();
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                "currencies differ: " + currency.getCurrencyCode() + " and " + other.currency.getCurrencyCode());
        }
    }
}
