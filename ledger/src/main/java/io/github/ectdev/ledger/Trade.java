package io.github.ectdev.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** One buy or sell that happened at a given moment. */
public record Trade(Instant at, Side side, BigDecimal quantity, BigDecimal unitPrice, Money fee) {

    public enum Side { BUY, SELL }

    public Trade {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(fee, "fee");
    }

    public static Trade buy(Instant at, String quantity, String unitPrice, Money fee) {
        return new Trade(at, Side.BUY, new BigDecimal(quantity), new BigDecimal(unitPrice), fee);
    }

    public static Trade sell(Instant at, String quantity, String unitPrice, Money fee) {
        return new Trade(at, Side.SELL, new BigDecimal(quantity), new BigDecimal(unitPrice), fee);
    }
}
