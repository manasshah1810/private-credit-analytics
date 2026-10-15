package dev.manas.credit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * A term loan held in a credit portfolio.
 *
 * <p>For {@link RateType#FIXED} loans {@code rate} is the annual cash coupon. For {@link RateType#FLOATING} loans it is the
 * spread over the reference index (e.g. SOFR), and the index is floored at {@code floor} before the spread is added.
 * {@code pikRate} is payment-in-kind interest: it is not paid in cash but added to the balance and repaid at maturity.
 * {@code price} is the purchase price as a percentage of par (98.5 = bought at a 1.5 point discount). All rates are
 * annual decimals (0.085 = 8.5%).
 */
public record Loan(String id, String borrower, String sector, BigDecimal principal, RateType rateType, BigDecimal rate,
                   BigDecimal floor, BigDecimal pikRate, DayCount dayCount, LocalDate start, LocalDate maturity,
                   int paymentsPerYear, Amortization amortization, BigDecimal price) {

    public enum Amortization { BULLET, STRAIGHT_LINE, LEVEL_PAYMENT }

    public enum RateType { FIXED, FLOATING }

    private static final BigDecimal PAR = BigDecimal.valueOf(100);

    public Loan {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (borrower == null || borrower.isBlank()) throw new IllegalArgumentException("borrower is required");
        if (sector == null || sector.isBlank()) throw new IllegalArgumentException("sector is required");
        if (principal == null || principal.signum() <= 0) throw new IllegalArgumentException("principal must be > 0");
        if (rateType == null) throw new IllegalArgumentException("rateType is required");
        if (rate == null || rate.signum() < 0) throw new IllegalArgumentException("rate must be >= 0");
        if (floor == null || floor.signum() < 0) throw new IllegalArgumentException("floor must be >= 0");
        if (rateType == RateType.FIXED && floor.signum() != 0)
            throw new IllegalArgumentException("floor applies only to floating-rate loans");
        if (pikRate == null || pikRate.signum() < 0) throw new IllegalArgumentException("pikRate must be >= 0");
        if (dayCount == null || amortization == null) throw new IllegalArgumentException("dayCount and amortization are required");
        if (amortization == Amortization.LEVEL_PAYMENT && pikRate.signum() > 0)
            throw new IllegalArgumentException("PIK is not supported on level-payment loans");
        if (price == null || price.signum() <= 0) throw new IllegalArgumentException("price must be > 0");
        if (start == null || maturity == null || !maturity.isAfter(start))
            throw new IllegalArgumentException("maturity must be after start");
        if (paymentsPerYear != 1 && paymentsPerYear != 2 && paymentsPerYear != 4 && paymentsPerYear != 12)
            throw new IllegalArgumentException("paymentsPerYear must be 1, 2, 4 or 12");
        long months = ChronoUnit.MONTHS.between(start, maturity);
        int step = 12 / paymentsPerYear;
        if (months == 0 || months % step != 0 || !start.plusMonths(months).equals(maturity))
            throw new IllegalArgumentException("term must be a whole number of payment periods ending exactly on maturity");
    }

    /** A fixed-rate, cash-pay loan bought at par. */
    public Loan(String id, String borrower, BigDecimal principal, BigDecimal annualRate, DayCount dayCount,
                LocalDate start, LocalDate maturity, int paymentsPerYear, Amortization amortization) {
        this(id, borrower, "Unassigned", principal, RateType.FIXED, annualRate, BigDecimal.ZERO, BigDecimal.ZERO,
                dayCount, start, maturity, paymentsPerYear, amortization, PAR);
    }

    public int periods() {
        return (int) (ChronoUnit.MONTHS.between(start, maturity) / (12 / paymentsPerYear));
    }

    public boolean isFloating() {
        return rateType == RateType.FLOATING;
    }

    /** Cash coupon for a period starting on {@code fixingDate}: the fixed rate, or max(index, floor) + spread. */
    public BigDecimal cashRate(RateCurve curve, LocalDate fixingDate) {
        if (!isFloating()) return rate;
        if (curve == null) throw new IllegalArgumentException("loan " + id + " is floating-rate and needs a rate curve");
        return curve.rateOn(fixingDate).max(floor).add(rate);
    }

    /** Amount paid to buy the loan: principal * price / 100. */
    public BigDecimal purchaseCost() {
        return principal.multiply(price).divide(PAR);
    }

    public Loan withSector(String newSector) {
        return new Loan(id, borrower, newSector, principal, rateType, rate, floor, pikRate, dayCount, start, maturity,
                paymentsPerYear, amortization, price);
    }

    public Loan withPik(BigDecimal newPikRate) {
        return new Loan(id, borrower, sector, principal, rateType, rate, floor, newPikRate, dayCount, start, maturity,
                paymentsPerYear, amortization, price);
    }

    public Loan withPrice(BigDecimal newPrice) {
        return new Loan(id, borrower, sector, principal, rateType, rate, floor, pikRate, dayCount, start, maturity,
                paymentsPerYear, amortization, newPrice);
    }

    public Loan floating(BigDecimal spread, BigDecimal indexFloor) {
        return new Loan(id, borrower, sector, principal, RateType.FLOATING, spread, indexFloor, pikRate, dayCount, start,
                maturity, paymentsPerYear, amortization, price);
    }
}
