package dev.manas.credit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Yield and duration on dated cash flows, using the same convention as Excel's XIRR: annual compounding with time
 * measured as actual days / 365 from the first cash flow.
 */
public final class Yield {
    private Yield() {}

    public record CashFlow(LocalDate date, BigDecimal amount) {}

    private static final double TOLERANCE = 1e-10;

    /**
     * Internal rate of return of irregular cash flows. Newton-Raphson from 10%, falling back to bisection on
     * [-99.99%, 1000%] if Newton does not converge. Needs at least one negative and one positive flow.
     */
    public static double xirr(List<CashFlow> flows) {
        if (flows.stream().noneMatch(f -> f.amount().signum() < 0) || flows.stream().noneMatch(f -> f.amount().signum() > 0))
            throw new IllegalArgumentException("XIRR needs at least one negative and one positive cash flow");
        LocalDate t0 = flows.stream().map(CashFlow::date).min(LocalDate::compareTo).orElseThrow();
        double[] t = new double[flows.size()];
        double[] a = new double[flows.size()];
        for (int i = 0; i < flows.size(); i++) {
            t[i] = ChronoUnit.DAYS.between(t0, flows.get(i).date()) / 365.0;
            a[i] = flows.get(i).amount().doubleValue();
        }
        double r = 0.1;
        for (int it = 0; it < 100; it++) {
            double f = npv(r, t, a);
            double df = 0;
            for (int i = 0; i < t.length; i++) df -= t[i] * a[i] / Math.pow(1 + r, t[i] + 1);
            if (df == 0 || Double.isNaN(df)) break;
            double next = r - f / df;
            if (next <= -1 || Double.isNaN(next) || Double.isInfinite(next)) break;
            if (Math.abs(next - r) < TOLERANCE) return next;
            r = next;
        }
        return bisect(t, a);
    }

    private static double bisect(double[] t, double[] a) {
        double lo = -0.9999;
        double hi = 10.0;
        double flo = npv(lo, t, a);
        if (flo * npv(hi, t, a) > 0) throw new IllegalArgumentException("XIRR has no root in [-99.99%, 1000%]");
        for (int it = 0; it < 300 && hi - lo > TOLERANCE; it++) {
            double mid = (lo + hi) / 2;
            double fm = npv(mid, t, a);
            if (flo * fm <= 0) {
                hi = mid;
            } else {
                lo = mid;
                flo = fm;
            }
        }
        return (lo + hi) / 2;
    }

    static double npv(double r, double[] t, double[] a) {
        double s = 0;
        for (int i = 0; i < t.length; i++) s += a[i] / Math.pow(1 + r, t[i]);
        return s;
    }

    /** The lender's cash flows: pay the purchase cost on the start date, then receive each period's cash. */
    public static List<CashFlow> lenderFlows(Loan loan, List<Payment> schedule) {
        List<CashFlow> out = new ArrayList<>(schedule.size() + 1);
        out.add(new CashFlow(loan.start(), loan.purchaseCost().negate()));
        for (Payment p : schedule) out.add(new CashFlow(p.date(), p.total()));
        return out;
    }

    /** Yield to maturity at the loan's purchase price. */
    public static double yieldToMaturity(Loan loan, List<Payment> schedule) {
        return xirr(lenderFlows(loan, schedule));
    }

    /**
     * Macaulay duration in years of the cash received, discounted at yield {@code y}. For floating-rate loans the
     * projected coupons move with the curve, so this measures sensitivity to the spread, not to the index.
     */
    public static double macaulayDuration(Loan loan, List<Payment> schedule, double y) {
        double pv = 0;
        double weighted = 0;
        for (Payment p : schedule) {
            double t = ChronoUnit.DAYS.between(loan.start(), p.date()) / 365.0;
            double v = p.total().doubleValue() / Math.pow(1 + y, t);
            pv += v;
            weighted += t * v;
        }
        return weighted / pv;
    }

    public static double modifiedDuration(Loan loan, List<Payment> schedule, double y) {
        return macaulayDuration(loan, schedule, y) / (1 + y);
    }
}
