package dev.manas.credit;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Default and recovery stress testing on scheduled cash flows.
 *
 * <p>A scenario has a constant annual default rate (CDR) and a recovery rate. Each period a surviving loan defaults with
 * probability q = 1 - (1 - CDR)^(1 / payments per year). If it defaults in period k, the lender recovers
 * {@code recovery * balance outstanding} on that payment date and loses the rest; otherwise it receives the scheduled
 * payment. Expected cash flow in period k is S(k) * payment(k) + S(k-1) * q * recovery * balance(k-1), where S is the
 * survival probability. Recoveries arrive with no lag, which flatters timing slightly.
 */
public final class Stress {
    private Stress() {}

    public record Scenario(String name, BigDecimal annualDefaultRate, BigDecimal recoveryRate) {
        public Scenario {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("scenario name is required");
            if (annualDefaultRate == null || annualDefaultRate.signum() < 0 || annualDefaultRate.compareTo(BigDecimal.ONE) >= 0)
                throw new IllegalArgumentException("default rate must be in [0, 1)");
            if (recoveryRate == null || recoveryRate.signum() < 0 || recoveryRate.compareTo(BigDecimal.ONE) > 0)
                throw new IllegalArgumentException("recovery rate must be in [0, 1]");
        }

        /** Parses {@code name,annualDefaultRate,recoveryRate} lines (first line is a header). */
        public static List<Scenario> parse(List<String> lines) {
            List<Scenario> out = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) continue;
                String[] f = lines.get(i).split(",", -1);
                try {
                    if (f.length != 3) throw new IllegalArgumentException("expected 3 columns, got " + f.length);
                    out.add(new Scenario(f[0].trim(), new BigDecimal(f[1].trim()), new BigDecimal(f[2].trim())));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("scenarios line " + (i + 1) + ": " + e.getMessage());
                }
            }
            return out;
        }
    }

    public record LoanResult(Loan loan, BigDecimal expectedLoss, List<Yield.CashFlow> expectedFlows, double expectedYield) {
        /** Expected loss as a share of original principal. */
        public BigDecimal lossRate() {
            return expectedLoss.divide(loan.principal(), 6, RoundingMode.HALF_EVEN);
        }
    }

    public record PortfolioResult(Scenario scenario, List<LoanResult> loans, BigDecimal expectedLoss, BigDecimal lossRate,
                                  Map<String, BigDecimal> lossBySector, double portfolioYield) {}

    private static final MathContext MC = MathContext.DECIMAL64;

    public static LoanResult run(Loan loan, List<Payment> schedule, Scenario s) {
        double q = 1 - Math.pow(1 - s.annualDefaultRate().doubleValue(), 1.0 / loan.paymentsPerYear());
        BigDecimal pd = BigDecimal.valueOf(q);
        BigDecimal recovery = s.recoveryRate();
        BigDecimal lossGivenDefault = BigDecimal.ONE.subtract(recovery);

        BigDecimal survival = BigDecimal.ONE;
        BigDecimal balanceBefore = loan.principal();
        BigDecimal loss = BigDecimal.ZERO;
        List<Yield.CashFlow> flows = new ArrayList<>(schedule.size() + 1);
        flows.add(new Yield.CashFlow(loan.start(), loan.purchaseCost().negate()));
        for (Payment p : schedule) {
            BigDecimal defaultProb = survival.multiply(pd, MC);
            BigDecimal next = survival.subtract(defaultProb, MC);
            BigDecimal cash = next.multiply(p.total(), MC).add(defaultProb.multiply(recovery, MC).multiply(balanceBefore, MC), MC);
            loss = loss.add(defaultProb.multiply(lossGivenDefault, MC).multiply(balanceBefore, MC), MC);
            flows.add(new Yield.CashFlow(p.date(), cash.setScale(2, RoundingMode.HALF_EVEN)));
            survival = next;
            balanceBefore = p.balanceAfter();
        }
        return new LoanResult(loan, loss.setScale(2, RoundingMode.HALF_EVEN), flows, Yield.xirr(flows));
    }

    public static PortfolioResult run(List<Loan> loans, RateCurve curve, Scenario s) {
        if (loans.isEmpty()) throw new IllegalArgumentException("no loans");
        List<LoanResult> results = new ArrayList<>(loans.size());
        List<Yield.CashFlow> all = new ArrayList<>();
        Map<String, BigDecimal> bySector = new TreeMap<>();
        BigDecimal loss = BigDecimal.ZERO;
        BigDecimal principal = BigDecimal.ZERO;
        for (Loan l : loans) {
            LoanResult r = run(l, Schedule.build(l, curve), s);
            results.add(r);
            all.addAll(r.expectedFlows());
            loss = loss.add(r.expectedLoss());
            principal = principal.add(l.principal());
            bySector.merge(l.sector(), r.expectedLoss(), BigDecimal::add);
        }
        return new PortfolioResult(s, results, loss, loss.divide(principal, 6, RoundingMode.HALF_EVEN), bySector,
                Yield.xirr(all));
    }
}
