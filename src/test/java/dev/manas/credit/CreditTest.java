package dev.manas.credit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.manas.credit.Covenants.Financials;
import dev.manas.credit.Covenants.Limits;
import dev.manas.credit.Loan.Amortization;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreditTest {
    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static LocalDate dt(String s) {
        return LocalDate.parse(s);
    }

    private static Loan loan(String principal, String rate, DayCount dc, String start, String maturity, int ppy, Amortization am) {
        return new Loan("L1", "Acme", d(principal), d(rate), dc, dt(start), dt(maturity), ppy, am);
    }

    // ---- day count ------------------------------------------------------------------------------------------

    @Test
    void act360CountsActualDays() {
        assertEquals(0, d("0.25").compareTo(DayCount.ACT_360.yearFraction(dt("2026-01-01"), dt("2026-04-01"))));
    }

    @Test
    void act365UsesActualOver365() {
        assertEquals(0, BigDecimal.ONE.compareTo(DayCount.ACT_365.yearFraction(dt("2026-01-01"), dt("2027-01-01"))));
    }

    @Test
    void thirty360HandlesMonthEnds() {
        assertEquals(0, d("28").divide(d("360"), java.math.MathContext.DECIMAL64)
                .compareTo(DayCount.THIRTY_360.yearFraction(dt("2026-01-31"), dt("2026-02-28"))));
        assertEquals(0, d("60").divide(d("360"), java.math.MathContext.DECIMAL64)
                .compareTo(DayCount.THIRTY_360.yearFraction(dt("2026-01-30"), dt("2026-03-31"))));
        assertEquals(0, BigDecimal.ONE.compareTo(DayCount.THIRTY_360.yearFraction(dt("2026-01-15"), dt("2027-01-15"))));
    }

    // ---- schedules ------------------------------------------------------------------------------------------

    @Test
    void bulletLoanPaysInterestOnlyThenPrincipal() {
        Loan l = loan("1000000", "0.08", DayCount.ACT_360, "2026-01-01", "2027-01-01", 4, Amortization.BULLET);
        List<Payment> s = Schedule.build(l);
        assertEquals(4, s.size());
        assertEquals(d("20000.00"), s.get(0).interest());
        assertEquals(d("20222.22"), s.get(1).interest());
        assertEquals(d("20444.44"), s.get(2).interest());
        assertEquals(d("81111.10"), Metrics.totalInterest(s));
        assertEquals(0, s.get(2).principal().signum());
        assertEquals(d("1000000"), s.get(3).principal());
        assertEquals(0, s.get(3).balanceAfter().signum());
        assertEquals(dt("2027-01-01"), s.get(3).date());
    }

    @Test
    void straightLineRepaysEqualSlices() {
        Loan l = loan("120000", "0", DayCount.ACT_360, "2026-01-01", "2027-01-01", 4, Amortization.STRAIGHT_LINE);
        List<Payment> s = Schedule.build(l);
        String[] expected = {"90000", "60000", "30000", "0"};
        for (int i = 0; i < 4; i++) assertEquals(0, d(expected[i]).compareTo(s.get(i).balanceAfter()), "period " + (i + 1));
        assertEquals(0, d("30000").compareTo(s.get(0).principal()));
        assertEquals(d("0.00"), Metrics.totalInterest(s));
    }

    @Test
    void straightLineRoundingRemainderLandsInLastPayment() {
        Loan l = loan("100", "0", DayCount.ACT_360, "2026-01-01", "2026-04-01", 12, Amortization.STRAIGHT_LINE);
        List<Payment> s = Schedule.build(l);
        assertEquals(d("33.33"), s.get(0).principal());
        assertEquals(d("33.33"), s.get(1).principal());
        assertEquals(d("33.34"), s.get(2).principal());
        assertEquals(d("100.00"), s.stream().map(Payment::principal).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void levelPaymentMatchesAnnuityFormula() {
        Loan l = loan("100000", "0.12", DayCount.THIRTY_360, "2026-01-01", "2027-01-01", 12, Amortization.LEVEL_PAYMENT);
        List<Payment> s = Schedule.build(l);
        assertEquals(12, s.size());
        assertEquals(d("1000.00"), s.get(0).interest());
        assertEquals(d("7884.88"), s.get(0).principal()); // payment 8884.88 - interest 1000.00
        assertEquals(d("8884.88"), Schedule.levelPayment(d("100000"), d("0.01"), 12));
        assertEquals(0, s.get(11).balanceAfter().signum());
        assertEquals(0, d("100000").compareTo(s.stream().map(Payment::principal).reduce(BigDecimal.ZERO, BigDecimal::add)));
        for (int i = 1; i < s.size(); i++) {
            assertTrue(s.get(i).balanceAfter().compareTo(s.get(i - 1).balanceAfter()) < 0, "balance must fall every period");
        }
    }

    @Test
    void levelPaymentWithZeroRateIsStraightLine() {
        assertEquals(d("2500.00"), Schedule.levelPayment(d("10000"), BigDecimal.ZERO, 4));
    }

    // ---- loan validation ------------------------------------------------------------------------------------

    @Test
    void invalidLoansAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> loan("0", "0.05", DayCount.ACT_360, "2026-01-01", "2027-01-01", 4, Amortization.BULLET));
        assertThrows(IllegalArgumentException.class, () -> loan("100", "-0.01", DayCount.ACT_360, "2026-01-01", "2027-01-01", 4, Amortization.BULLET));
        assertThrows(IllegalArgumentException.class, () -> loan("100", "0.05", DayCount.ACT_360, "2027-01-01", "2026-01-01", 4, Amortization.BULLET));
        assertThrows(IllegalArgumentException.class, () -> loan("100", "0.05", DayCount.ACT_360, "2026-01-01", "2027-01-01", 3, Amortization.BULLET));
        // 14 months is not a whole number of quarters
        assertThrows(IllegalArgumentException.class, () -> loan("100", "0.05", DayCount.ACT_360, "2026-01-01", "2027-03-01", 4, Amortization.BULLET));
        // whole months but day-of-month does not line up with maturity
        assertThrows(IllegalArgumentException.class, () -> loan("100", "0.05", DayCount.ACT_360, "2026-01-01", "2027-01-15", 4, Amortization.BULLET));
    }

    // ---- metrics --------------------------------------------------------------------------------------------

    @Test
    void walOfBulletEqualsTermInYears() {
        Loan l = loan("1000000", "0.08", DayCount.ACT_360, "2026-01-01", "2027-01-01", 4, Amortization.BULLET);
        assertEquals(d("1.0000"), Metrics.weightedAverageLife(l, Schedule.build(l)));
    }

    @Test
    void amortizingLoanHasShorterWalThanBullet() {
        Loan bullet = loan("1000000", "0.08", DayCount.ACT_360, "2026-01-01", "2029-01-01", 4, Amortization.BULLET);
        Loan amort = loan("1000000", "0.08", DayCount.ACT_360, "2026-01-01", "2029-01-01", 4, Amortization.STRAIGHT_LINE);
        assertTrue(Metrics.weightedAverageLife(amort, Schedule.build(amort))
                .compareTo(Metrics.weightedAverageLife(bullet, Schedule.build(bullet))) < 0);
    }

    @Test
    void weightedAverageCouponIsPrincipalWeighted() {
        Loan a = loan("100", "0.05", DayCount.ACT_360, "2026-01-01", "2027-01-01", 4, Amortization.BULLET);
        Loan b = loan("300", "0.09", DayCount.ACT_360, "2026-01-01", "2027-01-01", 4, Amortization.BULLET);
        assertEquals(d("0.080000"), Metrics.weightedAverageCoupon(List.of(a, b)));
        assertThrows(IllegalArgumentException.class, () -> Metrics.weightedAverageCoupon(List.of()));
    }

    // ---- covenants ------------------------------------------------------------------------------------------

    private static final Limits LIMITS = new Limits(d("4.0"), d("3.0"));

    @Test
    void healthyBorrowerHasNoBreach() {
        assertTrue(Covenants.test(new Financials("A", d("300"), d("100"), d("20")), LIMITS).isEmpty());
    }

    @Test
    void leverageAboveMaximumBreaches() {
        var b = Covenants.test(new Financials("A", d("500"), d("100"), d("20")), LIMITS);
        assertEquals(1, b.size());
        assertEquals(Covenants.Test.LEVERAGE, b.get(0).test());
        assertEquals(d("5.00"), b.get(0).actual());
    }

    @Test
    void thresholdsAreInclusive() {
        // leverage exactly 4.00 and coverage exactly 3.00 are on the limit and pass
        assertTrue(Covenants.test(new Financials("A", d("360"), d("90"), d("30")), new Limits(d("4.0"), d("3.0"))).isEmpty());
        // one cent over/under the limit breaches
        assertEquals(1, Covenants.test(new Financials("A", d("360.9"), d("90"), d("30")), new Limits(d("4.0"), d("3.0"))).size());
        assertEquals(1, Covenants.test(new Financials("A", d("358"), d("89.7"), d("30")), new Limits(d("4.0"), d("3.0"))).size());
    }

    @Test
    void lowCoverageBreaches() {
        var b = Covenants.test(new Financials("A", d("100"), d("100"), d("50")), LIMITS);
        assertEquals(1, b.size());
        assertEquals(Covenants.Test.INTEREST_COVERAGE, b.get(0).test());
        assertEquals(d("2.00"), b.get(0).actual());
    }

    @Test
    void nonPositiveEbitdaBreachesWithUndefinedRatio() {
        var b = Covenants.test(new Financials("A", d("100"), d("0"), d("10")), LIMITS);
        assertEquals(2, b.size());
        assertNull(b.get(0).actual());
        var noInterest = Covenants.test(new Financials("A", d("100"), d("-5"), d("0")), LIMITS);
        assertEquals(1, noInterest.size());
    }

    @Test
    void zeroInterestPassesCoverage() {
        assertTrue(Covenants.test(new Financials("A", d("100"), d("100"), d("0")), LIMITS).isEmpty());
    }

    @Test
    void limitsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new Limits(d("0"), d("3")));
        assertThrows(IllegalArgumentException.class, () -> new Limits(d("4"), d("-1")));
    }

    // ---- CLI ------------------------------------------------------------------------------------------------

    @Test
    void cliPrintsReportAndBreaches(@TempDir Path dir) throws Exception {
        Path loans = dir.resolve("loans.csv");
        Files.writeString(loans, "id,borrower,principal,annualRate,dayCount,start,maturity,paymentsPerYear,amortization\n"
                + "L1,Acme,1000000,0.08,ACT_360,2026-01-01,2027-01-01,4,BULLET\n");
        Path fin = dir.resolve("fin.csv");
        Files.writeString(fin, "borrower,totalDebt,ebitda,interestExpense\nAcme,500,100,20\n");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Cli.run(new String[] {loans.toString(), fin.toString(), "4.0", "3.0"}, new PrintStream(buf));
        String out = buf.toString();
        assertTrue(out.contains("81111.10"), out);
        assertTrue(out.contains("weighted average coupon: 8.000%"), out);
        assertTrue(out.contains("BREACH Acme"), out);
        assertTrue(out.contains("1 covenant breach(es)"), out);
    }

    @Test
    void cliReportsLineNumbersOnBadInput() {
        var e = assertThrows(IllegalArgumentException.class, () -> Cli.parseLoans(List.of(
                "id,borrower,principal,annualRate,dayCount,start,maturity,paymentsPerYear,amortization",
                "L1,Acme,abc,0.08,ACT_360,2026-01-01,2027-01-01,4,BULLET")));
        assertTrue(e.getMessage().startsWith("loans line 2"), e.getMessage());
        var e2 = assertThrows(IllegalArgumentException.class, () -> Cli.parseFinancials(List.of("h", "Acme,1,2")));
        assertTrue(e2.getMessage().contains("expected 4 columns"));
    }
}
