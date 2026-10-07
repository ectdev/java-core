package io.github.ectdev.ledger;

import java.math.BigDecimal;

/** Thrown when a sale asks for more than is held. The book is left exactly as it was. */
public final class InsufficientQuantityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The quantity is a BigDecimal, which is serializable, but the exception is not meant to travel. */
    private final transient BigDecimal requested;
    private final transient BigDecimal held;

    InsufficientQuantityException(BigDecimal requested, BigDecimal held) {
        super("cannot sell " + requested.toPlainString() + ": only " + held.toPlainString() + " held (short by "
            + requested.subtract(held).toPlainString() + ")");
        this.requested = requested;
        this.held = held;
    }

    public BigDecimal requested() {
        return requested;
    }

    public BigDecimal held() {
        return held;
    }

    /** How much more was asked for than exists. */
    public BigDecimal shortBy() {
        return requested.subtract(held);
    }
}
