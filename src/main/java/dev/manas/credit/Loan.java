package dev.manas.credit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** A term loan. Rate is an annual decimal (0.085 = 8.5%). */
public record Loan(String id, String borrower, BigDecimal principal, BigDecimal annualRate, DayCount dayCount,
                   LocalDate start, LocalDate maturity, int paymentsPerYear, Amortization amortization) {

    public enum Amortization { BULLET, STRAIGHT_LINE, LEVEL_PAYMENT }

    public Loan {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (borrower == null || borrower.isBlank()) throw new IllegalArgumentException("borrower is required");
        if (principal == null || principal.signum() <= 0) throw new IllegalArgumentException("principal must be > 0");
        if (annualRate == null || annualRate.signum() < 0) throw new IllegalArgumentException("rate must be >= 0");
        if (dayCount == null || amortization == null) throw new IllegalArgumentException("dayCount and amortization are required");
        if (start == null || maturity == null || !maturity.isAfter(start))
            throw new IllegalArgumentException("maturity must be after start");
        if (paymentsPerYear != 1 && paymentsPerYear != 2 && paymentsPerYear != 4 && paymentsPerYear != 12)
            throw new IllegalArgumentException("paymentsPerYear must be 1, 2, 4 or 12");
        long months = ChronoUnit.MONTHS.between(start, maturity);
        int step = 12 / paymentsPerYear;
        if (months == 0 || months % step != 0 || !start.plusMonths(months).equals(maturity))
            throw new IllegalArgumentException("term must be a whole number of payment periods ending exactly on maturity");
    }

    public int periods() {
        return (int) (ChronoUnit.MONTHS.between(start, maturity) / (12 / paymentsPerYear));
    }
}
