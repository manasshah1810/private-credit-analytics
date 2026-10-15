package dev.manas.credit;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Builds a cash-flow schedule for a loan. All money is rounded to 2 decimals (HALF_EVEN) each period. */
public final class Schedule {
    private Schedule() {}

    public static List<Payment> build(Loan loan) {
        int n = loan.periods();
        int step = 12 / loan.paymentsPerYear();
        BigDecimal balance = loan.principal();
        BigDecimal periodicRate = loan.annualRate().divide(BigDecimal.valueOf(loan.paymentsPerYear()), MathContext.DECIMAL64);
        BigDecimal level = loan.amortization() == Loan.Amortization.LEVEL_PAYMENT ? levelPayment(loan.principal(), periodicRate, n) : null;
        BigDecimal straight = loan.principal().divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_EVEN);

        List<Payment> out = new ArrayList<>(n);
        LocalDate prev = loan.start();
        for (int k = 1; k <= n; k++) {
            LocalDate date = loan.start().plusMonths((long) step * k);
            BigDecimal interest = money(balance.multiply(loan.annualRate()).multiply(loan.dayCount().yearFraction(prev, date)));
            BigDecimal principal;
            if (k == n) {
                principal = balance; // final period always clears the balance
            } else {
                principal = switch (loan.amortization()) {
                    case BULLET -> BigDecimal.ZERO;
                    case STRAIGHT_LINE -> straight;
                    case LEVEL_PAYMENT -> level.subtract(interest).max(BigDecimal.ZERO).min(balance);
                };
            }
            balance = balance.subtract(principal);
            out.add(new Payment(date, interest, principal, balance));
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
