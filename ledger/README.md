# ledger

Money held as whole minor units, a way of dividing it that never loses a cent, and a first-in-first-out lot book that
works out what a sale actually earned. The last piece replays a whole trade history and refuses it if the position
would have gone below zero at any moment, which is the check that keeps a back-dated sale from inventing profit.

```java
Currency usd = Currency.getInstance("USD");
LotBook book = new LotBook(usd);
book.buy(new BigDecimal("10"), new BigDecimal("100"), Money.zero(usd));
book.buy(new BigDecimal("10"), new BigDecimal("200"), Money.zero(usd));

Sale sale = book.sell(new BigDecimal("12"), new BigDecimal("300"), Money.zero(usd));
sale.realized();   // 2200.00 USD: 3600 in, 10 at 100 and 2 at 200 (1400) out
book.lots();       // [Lot[quantity=8, cost=1600.00 USD]]
book.sell(new BigDecimal("9"), new BigDecimal("300"), Money.zero(usd));
// InsufficientQuantityException: cannot sell 9: only 8 held (short by 1)

Money.parse("1.00", usd).split(3);            // [0.34 USD, 0.33 USD, 0.33 USD]
Money.parse("0.05", usd).allocate(70, 30);    // [0.04 USD, 0.01 USD]

TradeJournal.replay(usd, List.of(
    Trade.buy(Instant.parse("2026-03-03T10:00:00Z"), "10", "100", Money.zero(usd)),
    Trade.sell(Instant.parse("2026-03-02T10:00:00Z"), "5", "150", Money.zero(usd))));
// ShortSaleException: the sale at 2026-03-02T10:00:00Z asks for 5 but only 0 was held then (short by 5)
```

These snippets are not decoration: [`ReadmeExampleTest`](src/test/java/io/github/ectdev/ledger/ReadmeExampleTest.java)
runs them, so they cannot drift from the code.

| Type | What it is |
| --- | --- |
| [`Money`](src/main/java/io/github/ectdev/ledger/Money.java) | An amount as a `long` count of the currency's minor unit; exact arithmetic that throws on overflow; `allocate` and `split` |
| [`LotBook`](src/main/java/io/github/ectdev/ledger/LotBook.java) | The purchases still held, oldest first; `buy`, and `sell` which consumes first in, first out |
| [`Lot`](src/main/java/io/github/ectdev/ledger/Lot.java), [`Sale`](src/main/java/io/github/ectdev/ledger/Sale.java) | What remains of a purchase; what a sale earned and which lots paid for it |
| [`TradeJournal`](src/main/java/io/github/ectdev/ledger/TradeJournal.java) | Replays dated trades in order and refuses a history that sells what was not yet owned |

## Decisions worth reading

**An amount is a whole number of cents.** A `double` cannot hold 0.10, and the error becomes visible after a few
additions. A `BigDecimal` can, but every sum then carries a scale that must be kept in step with the currency, and an
amount like 0.125 USD can exist although no such coin does. A `long` of minor units removes both problems: an amount is
always a real amount, addition is exact, `equals` is the obvious one. The number of decimals comes from the currency
(USD 2, JPY 0, KWD 3), and a pseudo-currency without a minor unit is refused at construction. Arithmetic that would
overflow throws instead of wrapping around, and amounts of different currencies refuse to combine: there is no single
right exchange rate to apply silently.

**Dividing a total never loses a cent.** One dollar split three ways is 0.333…, and rounding each share gives
0.33 + 0.33 + 0.33 = 0.99. `allocate` uses the largest remainder method: round every share *down*, which leaves fewer
units over than there are parties, and hand those out one each to the parties whose exact share had the biggest
fraction left. Ties go to the earlier party, so the answer is deterministic. What follows from this, and is tested on
thousands of random inputs including the extremes of a `long`: the shares add up to the total; no share is a full unit
away from the exact proportion; a larger weight never gets less than a smaller one; a zero weight gets nothing; the
shares of a negative total are the negated shares of its absolute value. The multiplication `total × weight` can reach
2¹²⁶, so it is done in `BigInteger`; a test mutates it to a `long` and watches it fail.

**Rounding is always stated.** `Money.multiply` and `Money.of` take the `RoundingMode` as a required argument, because
there is no neutral default: half-up favours the receiver, half-even is unbiased over many amounts, and the choice is
usually a contractual one. The lot book uses half-even throughout, and says so. `Money.parse` goes the other way and
never rounds at all: `1.500` USD is accepted, since nothing is lost, and `1.005` is refused.

**A lot's cost is an integer, and splitting it keeps both halves.** When a sale uses part of a lot, the natural
formula is `cost × used / quantity`, which rarely divides evenly. The sale gets that value rounded to a whole cent, and
the lot keeps *the original cost minus that*. The two integers add up to the original, so no cent is created or lost,
however many partial sales follow. The consequence is a conservation law that the tests check after every operation of
thousands of random ones: **the total bought equals the total of what sold plus what is still held**, in cents and in
quantity. The cost of a lot is stored, not a price per unit, because "the remaining 3 cost 301.00" always has an exact
form and "100.3333… each" does not.

**Fees travel with the lot.** A purchase fee is added to the lot's cost, so it comes back pro rata as the lot is sold.
A sale's own fee is subtracted from its proceeds. `Sale.realized()` is proceeds, less fee, less the cost of what was
sold, and nothing else.

**A sale either happens or leaves no trace.** Asking for more than is held throws `InsufficientQuantityException` and
changes nothing; so does any numeric overflow half-way through. Every operation builds its result first and replaces the
book's state in a single assignment at the end. A test makes two lots that each cost the most a `long` can hold and sells
both: the addition overflows in the middle of the loop, and the book must be exactly as it was.

**Quantities have one spelling.** `10`, `10.0` and `1E+1` are the same quantity, but as `BigDecimal`s they are three
unequal values. A `Lot` stores its quantity in a canonical form, so equality and the comparison of whole books behave.

**The whole timeline is checked, not the final quantity.** Entering a sale dated last Tuesday, or deleting an old
purchase, can leave the end result looking fine (zero held, say) while on Wednesday the book sold something it did not yet
own, and the profit computed from that is invented. `TradeJournal.replay` sorts the history by time and replays all of
it; the first sale that exceeds what was held *at that moment* refuses the whole history and names the moment and the
shortfall. A test sells 10 on the 2nd and buys 10 on the 3rd: the final position is a clean zero, and the history is
still refused. Trades at the same instant keep the order in which they were listed, so a purchase listed first covers
the sale, and the reverse does not.

## How it is checked

* **Hand-worked tables.** The textbook case (10 at 100, 10 at 200, sell 12 at 300 gives exactly 2,200) and every other
  scenario are written with the arithmetic in comments. The allocation table lists the floors, the remainders and who
  gets the leftover for each row, and includes the extremes of a `long` and the case that overflows a `long`
  (`Long.MAX_VALUE` split between `Long.MAX_VALUE` and 1).
* **An oracle that shares no arithmetic.** Over random buy and sell histories with whole units at whole-dollar prices
  (so no rounding can occur) the lot book is compared, after every operation, with a queue holding one entry *per unit
  bought*. A sale there is just removing units from the front and adding up what they cost. The book must agree on cost,
  proceeds, realized profit, quantity, cost basis and the exact list of lots. Oversells are mixed in and must be refused
  and change nothing.
* **Conservation where rounding does occur.** Fractional quantities, prices with up to four decimals and fees: no
  unit-by-unit answer exists, so what is checked is that nothing is created or lost, after every operation, and that
  every partly used lot hands over a share within half a cent of the exact proportion, while whole lots are handed over
  at exactly their cost.
* **A tie rounds to the even neighbour in both directions.** 1,000.5 goes down and 1,001.5 goes up, each with its own
  test, because a mutant that rounds one way passes the other.
* **Mutation checks.** Forty-eight deliberately broken copies of the code (ties
  going to the wrong party, `allocate` doing its multiplication in a `long`, a partly used lot keeping its whole cost,
  the newest lot consumed first, a sale committed before its profit is checked, a back-dated sale sorted after the
  purchase) are all caught; see [`tools/`](../tools/README.md). All 48 died on the first run, which is partly
  preparation: before running them I noticed that three of them (a missing empty-list check, a missing currency check
  on a fee, `split(0)`) change nothing but the *message* of an exception of the same type, so the tests that only
  asserted the type could not have told them apart. Those tests now assert the message.

## What is not covered

One currency per book: there is no conversion and no exchange rate. Only first in, first out; no average cost, no
specific-lot identification, no tax-lot rules. No splits, dividends or short positions (a negative quantity is
refused). `TradeJournal` orders by `Instant`; which *calendar day* a moment falls on, and in which time zone, is the
caller's decision, and an important one for any real profit-and-loss report. The book is not thread-safe and does not
persist anything. Quantities and prices are unbounded `BigDecimal`s, but every amount that results must fit a `long` of
minor units, and the code throws where it does not.

Run just this module with `./mvnw -pl ledger test`.
