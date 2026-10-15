package dev.manas.credit;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Usage: java -jar private-credit-analytics.jar --loans loans.csv [--curve curve.csv] [--financials fin.csv
 * --max-leverage 6.0 --min-coverage 2.0] [--scenarios scenarios.csv]
 *
 * <pre>
 * loans.csv:      id,borrower,sector,principal,rateType,rate,floor,pikRate,dayCount,start,maturity,paymentsPerYear,amortization,price
 * curve.csv:      date,rate
 * financials.csv: borrower,totalDebt,ebitda,interestExpense
 * scenarios.csv:  name,annualDefaultRate,recoveryRate
 * </pre>
 */
public final class Cli {
    private Cli() {}

    public static void main(String[] args) throws IOException {
        try {
            run(args, System.out);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println("usage: --loans loans.csv [--curve curve.csv] [--financials fin.csv --max-leverage X --min-coverage Y]"
                    + " [--scenarios scenarios.csv]");
            System.exit(1);
        }
    }

    static Map<String, String> flags(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) throw new IllegalArgumentException("bad argument: " + args[i]);
            m.put(args[i].substring(2), args[i + 1]);
        }
        return m;
    }

    static void run(String[] args, PrintStream out) throws IOException {
        Map<String, String> f = flags(args);
        if (!f.containsKey("loans")) throw new IllegalArgumentException("--loans is required");
        List<Loan> loans = parseLoans(Files.readAllLines(Path.of(f.get("loans"))));
        RateCurve curve = f.containsKey("curve") ? RateCurve.parse(Files.readAllLines(Path.of(f.get("curve")))) : null;
        for (Loan l : loans) l.cashRate(curve, l.start()); // fail early if a floating loan has no curve

        out.printf("%-6s %-20s %13s %7s %13s %12s %6s %7s %7s%n", "loan", "borrower", "principal", "coupon", "cash int.",
                "PIK", "WAL", "YTM", "mod.dur");
        for (Loan l : loans) {
            List<Payment> s = Schedule.build(l, curve);
            double ytm = Yield.yieldToMaturity(l, s);
            out.printf("%-6s %-20s %13s %6s%% %13s %12s %6s %6s%% %7.2f%n", l.id(), l.borrower(),
                    l.principal().toPlainString(), pct(l.cashRate(curve, l.start()).add(l.pikRate()), 2),
                    Metrics.totalInterest(s).toPlainString(), Metrics.totalPik(s).toPlainString(),
                    Metrics.weightedAverageLife(l, s).toPlainString(), pct(BigDecimal.valueOf(ytm), 2),
                    Yield.modifiedDuration(l, s, ytm));
        }
        out.println("weighted average coupon: " + pct(Metrics.weightedAverageCoupon(loans, curve), 3) + "%");
        out.print("sector concentration:");
        Metrics.sectorConcentration(loans).forEach((sector, share) -> out.print(" " + sector + " " + pct(share, 1) + "%;"));
        out.println();

        if (f.containsKey("financials")) {
            if (!f.containsKey("max-leverage") || !f.containsKey("min-coverage"))
                throw new IllegalArgumentException("--financials needs --max-leverage and --min-coverage");
            Covenants.Limits limits = new Covenants.Limits(new BigDecimal(f.get("max-leverage")), new BigDecimal(f.get("min-coverage")));
            int breaches = 0;
            for (Covenants.Financials fin : parseFinancials(Files.readAllLines(Path.of(f.get("financials"))))) {
                for (Covenants.Breach b : Covenants.test(fin, limits)) {
                    breaches++;
                    out.printf("BREACH %-20s %-17s actual=%s threshold=%s (%s)%n", b.borrower(), b.test(),
                            b.actual() == null ? "n/a" : b.actual().toPlainString(), b.threshold().toPlainString(), b.reason());
                }
            }
            out.println(breaches + " covenant breach(es)");
        }

        if (f.containsKey("scenarios")) {
            out.println();
            out.printf("%-12s %6s %9s %16s %10s %10s%n", "scenario", "CDR", "recovery", "expected loss", "loss rate", "port. YTM");
            for (Stress.Scenario s : Stress.Scenario.parse(Files.readAllLines(Path.of(f.get("scenarios"))))) {
                Stress.PortfolioResult r = Stress.run(loans, curve, s);
                out.printf("%-12s %5s%% %8s%% %16s %9s%% %9s%%%n", s.name(), pct(s.annualDefaultRate(), 1), pct(s.recoveryRate(), 0),
                        r.expectedLoss().toPlainString(), pct(r.lossRate(), 2), pct(BigDecimal.valueOf(r.portfolioYield()), 2));
            }
        }
    }

    private static String pct(BigDecimal v, int places) {
        return v.movePointRight(2).setScale(places, RoundingMode.HALF_EVEN).toPlainString();
    }

    static List<Loan> parseLoans(List<String> lines) {
        List<Loan> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            String[] f = lines.get(i).split(",", -1);
            try {
                if (f.length != 14) throw new IllegalArgumentException("expected 14 columns, got " + f.length);
                out.add(new Loan(f[0].trim(), f[1].trim(), f[2].trim(), new BigDecimal(f[3].trim()),
                        Loan.RateType.valueOf(f[4].trim()), new BigDecimal(f[5].trim()), new BigDecimal(f[6].trim()),
                        new BigDecimal(f[7].trim()), DayCount.valueOf(f[8].trim()), LocalDate.parse(f[9].trim()),
                        LocalDate.parse(f[10].trim()), Integer.parseInt(f[11].trim()), Loan.Amortization.valueOf(f[12].trim()),
                        new BigDecimal(f[13].trim())));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("loans line " + (i + 1) + ": " + e.getMessage());
            }
        }
        if (out.isEmpty()) throw new IllegalArgumentException("loans file has no loans");
        return out;
    }

    static List<Covenants.Financials> parseFinancials(List<String> lines) {
        List<Covenants.Financials> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            String[] f = lines.get(i).split(",", -1);
            try {
                if (f.length != 4) throw new IllegalArgumentException("expected 4 columns, got " + f.length);
                out.add(new Covenants.Financials(f[0].trim(), new BigDecimal(f[1].trim()), new BigDecimal(f[2].trim()),
                        new BigDecimal(f[3].trim())));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("financials line " + (i + 1) + ": " + e.getMessage());
            }
        }
        return out;
    }
}
