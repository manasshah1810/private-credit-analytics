# private-credit-analytics

[![CI](https://github.com/manasshah1810/private-credit-analytics/actions/workflows/ci.yml/badge.svg)](https://github.com/manasshah1810/private-credit-analytics/actions/workflows/ci.yml)

A small, dependency-free Java 17 library and CLI for the core arithmetic of private credit: building a term-loan
cash-flow schedule, measuring weighted average life (WAL) and coupon, and testing financial covenants.
It was written to understand the kind of calculations a credit portfolio team relies on, not to replace a loan system.

## Features

- **Day counts:** ACT/360, ACT/365, 30/360 (US bond basis, including month-end rules).
- **Schedules:** bullet, straight-line and level-payment (annuity) loans; payment frequency 1, 2, 4 or 12 per year.
  Money is `BigDecimal`, rounded to 2 decimals (HALF_EVEN) each period; the final payment clears any rounding remainder.
- **Metrics:** total interest, WAL (ACT/365), principal-weighted average coupon.
- **Covenants:** leverage (debt / EBITDA, max) and interest coverage (EBITDA / interest, min). Limits are inclusive.
  Non-positive EBITDA is a breach with an undefined ratio; zero interest passes coverage.
- **Validation:** a loan's term must be a whole number of payment periods ending exactly on maturity; bad CSV rows are
  reported with the line number.

## Run

```bash
mvn verify
java -jar target/private-credit-analytics-1.0.0.jar samples/loans.csv samples/financials.csv 6.0 2.0
```

Sample output (synthetic data):

```
loan     borrower            principal   coupon     interest    WAL
L1       Northwind Logistics       25000000    9.25%  11729513.86 5.0027
L2       Helios Solar         40000000     8.5%  13484539.20 3.9712
L3       Brightline Retail       15000000    10.5%   2464461.80 1.5433
weighted average coupon: 9.109%
BREACH Helios Solar   LEVERAGE          actual=6.34 threshold=6.0 (leverage above maximum)
BREACH Brightline Retail LEVERAGE          actual=9.60 threshold=6.0 (leverage above maximum)
BREACH Brightline Retail INTEREST_COVERAGE actual=0.96 threshold=2.0 (coverage below minimum)
3 covenant breach(es)
```

## Tests

21 JUnit 5 tests with hand-computed expectations, for example: a 1,000,000 8% quarterly ACT/360 bullet loan pays
20,000.00 / 20,222.22 / 20,444.44 / 20,444.44 of interest (total 81,111.10); a 100,000 12% monthly level-payment loan has a
payment of 8,884.88; 30/360 month-end cases; inclusive covenant boundaries; CLI output and error line numbers.

## Limitations

- Fixed-rate loans only: no floating-rate indices, spreads, floors, PIK, fees, prepayments or defaults.
- Level-payment uses the nominal periodic rate (rate / payments per year) to size the payment, while interest accrues on the
  chosen day count, so the last payment absorbs the difference.
- No business-day adjustment or holiday calendars. Single currency per loan; no FX.
- Synthetic sample data. Not financial advice and not a valuation model.
