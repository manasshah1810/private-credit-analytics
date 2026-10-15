package dev.manas.credit;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Usage: java -jar private-credit-analytics.jar loans.csv [financials.csv maxLeverage minCoverage]
 * loans.csv:      id,borrower,principal,annualRate,dayCount,start,maturity,paymentsPerYear,amortization
 * financials.csv: borrower,totalDebt,ebitda,interestExpense
 */
public final class Cli {
    private Cli() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1 && args.length != 4) {
            System.err.println("usage: loans.csv [financials.csv maxLeverage minCoverage]");
            System.exit(2);
        }
        try {
            run(args, System.out);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    static void run(String[] args, java.io.PrintStream out) throws IOException {
        List<Loan> loans = parseLoans(Files.readAllLines(Path.of(args[0])));
        out.printf("%-8s %-14s %14s %8s %12s %6s%n", "loan", "borrower", "principal", "coupon", "interest", "WAL");
        for (Loan l : loans) {
            List<Payment> s = Schedule.build(l);
            out.printf("%-8s %-14s %14s %7s%% %12s %6s%n", l.id(), l.borrower(), l.principal().toPlainString(),
                    l.annualRate().movePointRight(2).stripTrailingZeros().toPlainString(),
                    Metrics.totalInterest(s).toPlainString(), Metrics.weightedAverageLife(l, s).toPlainString());
        }
        out.println("weighted average coupon: "
                + Metrics.weightedAverageCoupon(loans).movePointRight(2).setScale(3, RoundingMode.HALF_EVEN).toPlainString() + "%");
        if (args.length == 4) {
            Covenants.Limits limits = new Covenants.Limits(new BigDecimal(args[2]), new BigDecimal(args[3]));
            int breaches = 0;
            for (Covenants.Financials f : parseFinancials(Files.readAllLines(Path.of(args[1])))) {
                for (Covenants.Breach b : Covenants.test(f, limits)) {
                    breaches++;
                    out.printf("BREACH %-14s %-17s actual=%s threshold=%s (%s)%n", b.borrower(), b.test(),
                            b.actual() == null ? "n/a" : b.actual().toPlainString(), b.threshold().toPlainString(), b.reason());
                }
            }
            out.println(breaches + " covenant breach(es)");
        }
    }

    static List<Loan> parseLoans(List<String> lines) {
        List<Loan> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            String[] f = lines.get(i).split(",", -1);
            try {
                if (f.length != 9) throw new IllegalArgumentException("expected 9 columns, got " + f.length);
                out.add(new Loan(f[0].trim(), f[1].trim(), new BigDecimal(f[2].trim()), new BigDecimal(f[3].trim()),
                        DayCount.valueOf(f[4].trim()), LocalDate.parse(f[5].trim()), LocalDate.parse(f[6].trim()),
                        Integer.parseInt(f[7].trim()), Loan.Amortization.valueOf(f[8].trim())));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("loans line " + (i + 1) + ": " + e.getMessage());
            }
        }
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
