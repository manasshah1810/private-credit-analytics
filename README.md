# private-credit-analytics

[![CI](https://github.com/manasshah1810/private-credit-analytics/actions/workflows/ci.yml/badge.svg)](https://github.com/manasshah1810/private-credit-analytics/actions/workflows/ci.yml)

A dependency-free Java 17 library and CLI for the core arithmetic of private credit: cash-flow schedules for fixed and
floating-rate term loans (with PIK), yield and duration, covenant tests, sector concentration, and default/recovery stress
tests. It was written to understand the calculations a credit portfolio team relies on, not to replace a loan system.

## Features

- **Day counts:** ACT/360, ACT/365, 30/360 (US bond basis, including month-end rules).
- **Loan types:** fixed or floating (index from a rate curve, floored, plus spread), optional **PIK** interest that
  capitalises into the balance and is repaid at maturity, purchase price (discount or premium to par), sector tag.
- **Schedules:** bullet, straight-line and level-payment (annuity); payment frequency 1, 2, 4 or 12 per year. Floating
  level-payment loans re-size the payment at each reset so they still amortise to zero. Money is `BigDecimal`, rounded to
  2 decimals (HALF_EVEN) per period; the last payment clears any rounding remainder.
- **Yield and duration:** XIRR (Excel convention: annual compounding, actual days / 365) by Newton-Raphson with a bisection
  fallback; yield to maturity at the purchase price; Macaulay and modified duration.
- **Portfolio metrics:** WAL (ACT/365), principal-weighted all-in coupon (cash + PIK), sector concentration.
- **Covenants:** leverage (debt / EBITDA, max) and interest coverage (EBITDA / interest, min). Limits are inclusive.
  Non-positive EBITDA is a breach with an undefined ratio; zero interest passes coverage.
- **Stress testing:** constant annual default rate and recovery rate per scenario. Each period a surviving loan defaults
  with probability `1 - (1 - CDR)^(1/payments per year)`; on default the lender recovers `recovery x balance` and loses
  the rest. Reports expected loss (total, per loan, per sector), loss rate and the portfolio's expected yield.
- **Validation:** a loan's term must be a whole number of payment periods ending exactly on maturity; bad CSV rows are
  reported with the line number; a floating loan without a rate curve is rejected up front.

## Run

```bash
mvn verify
java -jar target/private-credit-analytics-1.0.0.jar --loans samples/loans.csv --curve samples/curve.csv \
  --financials samples/financials.csv --max-leverage 6.0 --min-coverage 2.0 --scenarios samples/scenarios.csv
```

Input files: see the header comment in [Cli.java](src/main/java/dev/manas/credit/Cli.java) and the files in `samples/`.

Sample output (synthetic data):

```
loan   borrower                 principal  coupon     cash int.          PIK    WAL     YTM mod.dur
L1     Northwind Logistics       25000000   9.25%   11729513.86         0.00 5.0027  10.12%    3.67
L2     Helios Solar              40000000   8.50%   13484539.20         0.00 3.9712   8.76%    3.02
L3     Brightline Retail         15000000  12.80%    2512040.93    488350.41 1.5893  15.68%    1.21
L4     Cobalt Software           30000000  10.05%   14348791.69         0.00 5.0027  10.22%    3.64
weighted average coupon: 9.680%
sector concentration: Consumer 13.6%; Energy 36.4%; Industrials 22.7%; Technology 27.3%;
BREACH Helios Solar         LEVERAGE          actual=6.34 threshold=6.0 (leverage above maximum)
BREACH Brightline Retail    LEVERAGE          actual=9.60 threshold=6.0 (leverage above maximum)
BREACH Brightline Retail    INTEREST_COVERAGE actual=0.96 threshold=2.0 (coverage below minimum)
3 covenant breach(es)

scenario        CDR  recovery    expected loss  loss rate  port. YTM
base           1.0%       65%       1571520.55      1.43%      9.59%
downside       4.0%       50%       8480250.44      7.71%      7.74%
severe        10.0%       35%      24610889.44     22.37%      2.70%
```

L1 is bought at 98.5, so its yield (10.12%) sits above its 9.25% coupon; L3 pays a floored index plus 6.5% spread in cash and
2% PIK.

## Tests

48 JUnit 5 tests with hand-computed expectations, for example: a 1,000,000 8% quarterly ACT/360 bullet pays 20,000.00 /
20,222.22 / 20,444.44 / 20,444.44 of interest (total 81,111.10); a 100,000 12% monthly level-payment loan has a payment of
8,884.88; XIRR of -1000 then +1100 a year later is exactly 10%; for a one-year bullet at a 10% default rate and 40% recovery
the expected loss is 60,000 and the expected cash at maturity is 1,012,000; PIK is repaid in full with the final payment; a
floor binds when the index falls below it; inclusive covenant boundaries; CLI output and error line numbers.

## Limitations

- Constant default rate and no recovery lag; no correlation between borrowers. This is a simple expected-loss model, not a
  credit-risk model.
- Floating loans read the curve as a step function at each period start. No index-tenor conventions, lookbacks or caps.
- Level-payment sizes the payment from the nominal periodic rate while interest accrues on the chosen day count, so the last
  payment absorbs the difference. PIK is not supported on level-payment loans.
- No fees, prepayments, business-day adjustment or holiday calendars. Single currency; no FX.
- Synthetic sample data. Not financial advice and not a valuation model.
