package dev.manas.credit;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a cash-flow schedule for a loan. All money is rounded to 2 decimals (HALF_EVEN) each period.
 *
 * <p>Floating-rate loans fix at the start of each period (index on that date, floored, plus spread). Level-payment
 * floating loans re-size the payment at every reset over the remaining periods, so the loan still amortises to zero.
 * PIK interest is added to the balance and repaid with the final payment.
 */
public final class Schedule {
    private Schedule() {}

    public static List<Payment> build(Loan loan) {
        return build(loan, null);
    }

    public static List<Payment> build(Loan loan, RateCurve curve) {
        int n = loan.periods();
        int step = 12 / loan.paymentsPerYear();
        BigDecimal ppy = BigDecimal.valueOf(loan.paymentsPerYear());
        BigDecimal balance = loan.principal();
        BigDecimal straight = loan.principal().divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_EVEN);
        BigDecimal fixedLevel = null;
        if (loan.amortization() == Loan.Amortization.LEVEL_PAYMENT && !loan.isFloating()) {
            fixedLevel = levelPayment(loan.principal(), loan.rate().divide(ppy, MathContext.DECIMAL64), n);
        }

        List<Payment> out = new ArrayList<>(n);
        LocalDate prev = loan.start();
        for (int k = 1; k <= n; k++) {
            LocalDate date = loan.start().plusMonths((long) step * k);
            BigDecimal cashRate = loan.cashRate(curve, prev);
            BigDecimal yf = loan.dayCount().yearFraction(prev, date);
            BigDecimal interest = money(balance.multiply(cashRate).multiply(yf));
            BigDecimal pik = money(balance.multiply(loan.pikRate()).multiply(yf));
            BigDecimal principal;
            if (k == n) {
                principal = balance.add(pik); // final period clears the balance, capitalised PIK and rounding remainders
            } else {
                principal = switch (loan.amortization()) {
                    case BULLET -> BigDecimal.ZERO;
                    case STRAIGHT_LINE -> straight.min(balance);
                    case LEVEL_PAYMENT -> {
                        BigDecimal level = fixedLevel != null ? fixedLevel
                                : levelPayment(balance, cashRate.divide(ppy, MathContext.DECIMAL64), n - k + 1);
                        yield level.subtract(interest).max(BigDecimal.ZERO).min(balance);
                    }
                };
            }
            balance = balance.add(pik).subtract(principal);
            out.add(new Payment(date, cashRate, interest, pik, principal, balance));
            prev = date;
        }
        return out;
    }

    /** Standard annuity payment: P*r / (1 - (1+r)^-n). Zero rate falls back to P/n. */
    static BigDecimal levelPayment(BigDecimal principal, BigDecimal r, int n) {
        if (r.signum() == 0) return principal.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_EVEN);
        BigDecimal growth = BigDecimal.ONE.add(r).pow(n, MathContext.DECIMAL64);
        BigDecimal discount = BigDecimal.ONE.divide(growth, MathContext.DECIMAL64);
        return money(principal.multiply(r).divide(BigDecimal.ONE.subtract(discount), MathContext.DECIMAL64));
    }

    static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN);
    }
}
