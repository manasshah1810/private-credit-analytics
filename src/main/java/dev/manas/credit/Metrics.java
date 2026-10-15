package dev.manas.credit;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Loan and portfolio analytics. */
public final class Metrics {
    private Metrics() {}

    public static BigDecimal totalInterest(List<Payment> schedule) {
        return schedule.stream().map(Payment::interest).reduce(BigDecimal.ZERO, BigDecimal::add);
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

    /** Principal-weighted average coupon across loans (annual, decimal). */
    public static BigDecimal weightedAverageCoupon(List<Loan> loans) {
        if (loans.isEmpty()) throw new IllegalArgumentException("no loans");
        BigDecimal total = loans.stream().map(Loan::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal weighted = loans.stream().map(l -> l.principal().multiply(l.annualRate())).reduce(BigDecimal.ZERO, BigDecimal::add);
        return weighted.divide(total, 6, RoundingMode.HALF_EVEN);
    }
}
