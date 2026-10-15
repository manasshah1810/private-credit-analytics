package dev.manas.credit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Financial covenant tests on borrower financials. */
public final class Covenants {
    private Covenants() {}

    public record Financials(String borrower, BigDecimal totalDebt, BigDecimal ebitda, BigDecimal interestExpense) {}

    public record Limits(BigDecimal maxLeverage, BigDecimal minInterestCoverage) {
        public Limits {
            if (maxLeverage == null || maxLeverage.signum() <= 0) throw new IllegalArgumentException("maxLeverage must be > 0");
            if (minInterestCoverage == null || minInterestCoverage.signum() <= 0)
                throw new IllegalArgumentException("minInterestCoverage must be > 0");
        }
    }

    public enum Test { LEVERAGE, INTEREST_COVERAGE }

    /** actual is null when the ratio is undefined (non-positive EBITDA for leverage). */
    public record Breach(String borrower, Test test, BigDecimal actual, BigDecimal threshold, String reason) {}

    /**
     * Leverage = debt / EBITDA must be <= maxLeverage. A borrower with EBITDA <= 0 breaches (ratio undefined).
     * Interest coverage = EBITDA / interest must be >= minInterestCoverage. Zero interest passes (no interest to cover).
     */
    public static List<Breach> test(Financials f, Limits limits) {
        List<Breach> out = new ArrayList<>();
        if (f.ebitda().signum() <= 0) {
            out.add(new Breach(f.borrower(), Test.LEVERAGE, null, limits.maxLeverage(), "EBITDA is not positive"));
            if (f.interestExpense().signum() > 0) {
                out.add(new Breach(f.borrower(), Test.INTEREST_COVERAGE, null, limits.minInterestCoverage(), "EBITDA is not positive"));
            }
            return out;
        }
        BigDecimal leverage = f.totalDebt().divide(f.ebitda(), 2, RoundingMode.HALF_EVEN);
        if (leverage.compareTo(limits.maxLeverage()) > 0) {
            out.add(new Breach(f.borrower(), Test.LEVERAGE, leverage, limits.maxLeverage(), "leverage above maximum"));
        }
        if (f.interestExpense().signum() > 0) {
            BigDecimal coverage = f.ebitda().divide(f.interestExpense(), 2, RoundingMode.HALF_EVEN);
            if (coverage.compareTo(limits.minInterestCoverage()) < 0) {
                out.add(new Breach(f.borrower(), Test.INTEREST_COVERAGE, coverage, limits.minInterestCoverage(), "coverage below minimum"));
            }
        }
        return out;
    }
}
