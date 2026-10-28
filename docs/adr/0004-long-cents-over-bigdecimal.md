# 0004. Long cents over BigDecimal

Status: accepted (Phase 1)

## Context

Money must add up exactly. Every entry sums to zero per currency, and the recount compares each stored balance with the sum of its lines for equality. A rounding step, a lost fraction or a silent overflow would show up as drift, or might not show up at all. The ledger stores and moves amounts and does no interest, fees or currency conversion, so it never needs a fraction of a currency's smallest unit.

## Decision

- An amount is a whole number in the currency's smallest unit, paired with a 3-letter uppercase code: `record Money(long minor, String currency)` (`common/Money.java`).
  - `plus` uses `Math.addExact` and refuses mismatched currencies.
  - `negate` uses `Math.negateExact`.
  - Both throw on overflow instead of wrapping.

  `PostingServiceImpl` turns that exception into a 422, "Amounts are too large to add exactly".
- Amount columns are `bigint`.
- Requests take amounts as JSON integers (`Long` fields in `TransferRequest` and `LineRequest`). Because `spring.jackson.deserialization.accept-float-as-int` is `false`, `100.75` gets a 400. Jackson used to truncate it to 100, until a Phase 2A test found this and `bfac303` fixed it.
- A currency is checked as 3 uppercase letters in `Money`, in Bean Validation and in the schema. A line's currency must equal its account's (composite foreign key, `V2__journal.sql`).
- Currencies are never converted or mixed. An entry balances in each currency on its own, so the ledger never needs to know how many decimal places a currency has.

## Consequences

Good:

- Arithmetic is exact, and comparing two amounts is `==` on longs, with no scale to agree on.
- Overflow becomes a refusal, not a wrong number. `PostingApiIT.amountsThatOverflowAreRefusedNotWrapped` posts `Long.MAX_VALUE`, `Long.MAX_VALUE` and -2 and gets a 422 with no lines written. `MoneyTest` (16 tests) covers overflow both ways, negating `Long.MIN_VALUE`, mismatched currencies and bad codes. `PostingApiIT.aFractionalAmountIs400NotTruncated` guards the Phase 2A fix.
- PostgreSQL's `sum` over `bigint` returns `numeric`, so sums in SQL (the recount and asOf) cannot wrap either.

Costs:

- No fractional cents. Sub-cent work such as accrued interest would need another unit.
- An amount or balance can be at most `Long.MAX_VALUE` in the smallest unit.
- `requireBalanced` sums lines in request order, so a valid entry whose running sum overflows (MAX, MAX, -MAX, -MAX) is refused with a 422 ([PROGRESS.md](../../PROGRESS.md) Ideas).
- The simulation never reaches overflow, because its amounts stay at a few thousand cents (PROGRESS.md Ideas).
- Clients must know each currency's smallest unit, and a client that parses JSON numbers as doubles loses exactness on very large amounts.

## Alternatives

- `BigDecimal`. Exact, but every value carries a scale, `equals` treats one amount at two scales as different, and any division or `setScale` is a place to round by accident. CLAUDE.md rules it out of domain code.
- `double` or `float`. Binary floating point cannot represent most decimal amounts exactly, so sums drift.
- PostgreSQL `numeric` columns. These return `BigDecimal` to Java and bring the same problems into the code.
- A money library such as JSR 354. It provides conversion and rounding the ledger does not use, and it is a dependency the plan does not list.
