package dev.manas.credit;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Loan and portfolio analytics. */
public final class Metrics {
    private Metrics() {}

    public static BigDecimal totalInterest(List<Payment> schedule) {
        return schedule.stream().map(Payment::interest).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static BigDecimal totalPik(List<Payment> schedule) {
        return schedule.stream().map(Payment::pikInterest).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Weighted average life in years (ACT/365): sum(principal_t * t) / sum(principal_t). */
    public static BigDecimal weightedAverageLife(Loan loan, List<Payment> schedule) {
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal repaid = BigDecimal.ZERO;
        for (Payment p : schedule) {
            BigDecimal years = BigDecimal.valueOf(ChronoUnit.DAYS.between(loan.start(), p.date()))
                    .divide(BigDecimal.valueOf(365), MathContext.DECIMAL64);
            weighted = weighted.add(p.principal().multiply(years));
            repaid = repaid.add(p.principal());
        }
        return weighted.divide(repaid, 4, RoundingMode.HALF_EVEN);
    }

    /** Principal-weighted average coupon across fixed-rate loans (annual, decimal). */
    public static BigDecimal weightedAverageCoupon(List<Loan> loans) {
        return weightedAverageCoupon(loans, null);
    }

    /**
     * Principal-weighted average all-in coupon (cash + PIK). Floating loans use their first-period rate from the curve.
     */
    public static BigDecimal weightedAverageCoupon(List<Loan> loans, RateCurve curve) {
        if (loans.isEmpty()) throw new IllegalArgumentException("no loans");
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal weighted = BigDecimal.ZERO;
        for (Loan l : loans) {
            total = total.add(l.principal());
            weighted = weighted.add(l.principal().multiply(l.cashRate(curve, l.start()).add(l.pikRate())));
        }
        return weighted.divide(total, 6, RoundingMode.HALF_EVEN);
    }

    /** Share of total principal by sector, as decimals that sum to 1 (to 6 places), sorted by sector name. */
    public static Map<String, BigDecimal> sectorConcentration(List<Loan> loans) {
        if (loans.isEmpty()) throw new IllegalArgumentException("no loans");
        BigDecimal total = loans.stream().map(Loan::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, BigDecimal> bySector = new TreeMap<>();
        for (Loan l : loans) bySector.merge(l.sector(), l.principal(), BigDecimal::add);
        bySector.replaceAll((s, v) -> v.divide(total, 6, RoundingMode.HALF_EVEN));
        return bySector;
    }
}
