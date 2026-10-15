package dev.manas.credit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.manas.credit.Loan.Amortization;
import dev.manas.credit.Loan.RateType;
import dev.manas.credit.Yield.CashFlow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdvancedTest {
    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static LocalDate dt(String s) {
        return LocalDate.parse(s);
    }

    private static Loan fixed(String principal, String rate, String start, String maturity, int ppy, Amortization am, String price) {
        return new Loan("L", "Acme", "Tech", d(principal), RateType.FIXED, d(rate), BigDecimal.ZERO, BigDecimal.ZERO,
                DayCount.ACT_365, dt(start), dt(maturity), ppy, am, d(price));
    }

    private static Loan floating(String principal, String spread, String floor, String pik, Amortization am) {
        return new Loan("F", "Acme", "Tech", d(principal), RateType.FLOATING, d(spread), d(floor), d(pik),
                DayCount.ACT_360, dt("2026-01-01"), dt("2027-01-01"), 4, am, d("100"));
    }

    // ---- rate curve and floating-rate loans ---------------------------------------------------------------------

    @Test
    void curveIsAStepFunctionOfLatestFixing() {
        RateCurve c = RateCurve.of(Map.of(dt("2026-01-01"), d("0.04"), dt("2026-07-01"), d("0.03")));
        assertEquals(d("0.04"), c.rateOn(dt("2026-06-30")));
        assertEquals(d("0.03"), c.rateOn(dt("2026-07-01")));
        assertEquals(d("0.03"), c.rateOn(dt("2030-01-01")));
        assertThrows(IllegalArgumentException.class, () -> c.rateOn(dt("2025-12-31")));
    }

    @Test
    void curveParsingReportsLineNumbers() {
        assertEquals(d("0.05"), RateCurve.parse(List.of("date,rate", "2026-01-01,0.05")).rateOn(dt("2026-02-01")));
        var e = assertThrows(IllegalArgumentException.class, () -> RateCurve.parse(List.of("date,rate", "2026-01-01,0.05", "oops,0.1")));
        assertTrue(e.getMessage().startsWith("curve line 3"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> RateCurve.parse(List.of("date,rate", "2026-01-01,0.05", "2026-01-01,0.06")));
        assertThrows(IllegalArgumentException.class, () -> RateCurve.parse(List.of("date,rate")));
    }

    @Test
    void floatingCouponIsIndexPlusSpread() {
        Loan l = floating("1000000", "0.05", "0", "0", Amortization.BULLET);
        List<Payment> s = Schedule.build(l, RateCurve.flat(d("0.04")));
        assertEquals(d("0.09"), s.get(0).couponRate());
        assertEquals(d("22500.00"), s.get(0).interest()); // 1,000,000 * 9% * 90/360
    }

    @Test
    void indexFloorBindsWhenIndexFallsBelowIt() {
        Loan l = floating("1000000", "0.05", "0.02", "0", Amortization.BULLET);
        assertEquals(d("0.07"), Schedule.build(l, RateCurve.flat(d("0.005"))).get(0).couponRate());
        assertEquals(d("0.09"), Schedule.build(l, RateCurve.flat(d("0.04"))).get(0).couponRate());
    }

    @Test
    void couponResetsWhenTheCurveMoves() {
        Loan l = floating("1000000", "0.05", "0", "0", Amortization.BULLET);
        RateCurve c = RateCurve.of(Map.of(dt("2026-01-01"), d("0.04"), dt("2026-04-01"), d("0.03")));
        List<Payment> s = Schedule.build(l, c);
        assertEquals(d("0.09"), s.get(0).couponRate());
        assertEquals(d("0.08"), s.get(1).couponRate()); // fixes on 2026-04-01, the period start
    }

    @Test
    void floatingLoanWithoutCurveIsRejected() {
        Loan l = floating("1000000", "0.05", "0", "0", Amortization.BULLET);
        assertThrows(IllegalArgumentException.class, () -> Schedule.build(l));
    }

    @Test
    void floatingLevelPaymentStillAmortisesToZero() {
        Loan l = floating("1000000", "0.05", "0", "0", Amortization.LEVEL_PAYMENT);
        RateCurve c = RateCurve.of(Map.of(dt("2026-01-01"), d("0.04"), dt("2026-07-01"), d("0.02")));
        List<Payment> s = Schedule.build(l, c);
        assertEquals(0, s.get(3).balanceAfter().signum());
        assertEquals(0, d("1000000").compareTo(s.stream().map(Payment::principal).reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    // ---- PIK ----------------------------------------------------------------------------------------------------

    @Test
    void pikAccruesIntoBalanceAndIsRepaidAtMaturity() {
        Loan l = floating("1000000", "0.06", "0", "0.02", Amortization.BULLET); // cash 10% (4% index), PIK 2%
        List<Payment> s = Schedule.build(l, RateCurve.flat(d("0.04")));
        assertEquals(d("5000.00"), s.get(0).pikInterest()); // 1,000,000 * 2% * 90/360
        assertEquals(d("1005000.00"), s.get(0).balanceAfter());
        assertTrue(s.get(1).pikInterest().compareTo(s.get(0).pikInterest()) > 0, "PIK compounds on the grown balance");
        assertEquals(0, s.get(3).balanceAfter().signum());
        BigDecimal repaid = s.stream().map(Payment::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, repaid.compareTo(d("1000000").add(Metrics.totalPik(s))), "principal repaid = original + all PIK");
        assertEquals(0, s.get(2).principal().signum(), "no cash principal before maturity");
    }

    @Test
    void pikIsNotAllowedOnLevelPaymentLoans() {
        assertThrows(IllegalArgumentException.class, () -> floating("1000000", "0.06", "0", "0.02", Amortization.LEVEL_PAYMENT));
    }

    @Test
    void allInCouponIncludesPik() {
        Loan l = floating("1000000", "0.06", "0", "0.02", Amortization.BULLET);
        assertEquals(d("0.120000"), Metrics.weightedAverageCoupon(List.of(l), RateCurve.flat(d("0.04"))));
    }

    // ---- loan validation ----------------------------------------------------------------------------------------

    @Test
    void newFieldsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new Loan("L", "A", "", d("1"), RateType.FIXED, d("0.05"), d("0"), d("0"),
                DayCount.ACT_360, dt("2026-01-01"), dt("2027-01-01"), 4, Amortization.BULLET, d("100")));
        assertThrows(IllegalArgumentException.class, () -> fixed("100", "0.05", "2026-01-01", "2027-01-01", 4, Amortization.BULLET, "0"));
        assertThrows(IllegalArgumentException.class, () -> new Loan("L", "A", "Tech", d("1"), RateType.FIXED, d("0.05"), d("0.01"),
                d("0"), DayCount.ACT_360, dt("2026-01-01"), dt("2027-01-01"), 4, Amortization.BULLET, d("100")));
        assertThrows(IllegalArgumentException.class, () -> floating("100", "0.05", "-0.01", "0", Amortization.BULLET));
    }

    @Test
    void purchaseCostIsPrincipalTimesPrice() {
        assertEquals(0, d("985000").compareTo(fixed("1000000", "0.05", "2026-01-01", "2027-01-01", 4, Amortization.BULLET, "98.5").purchaseCost()));
    }

    // ---- sector concentration -----------------------------------------------------------------------------------

    @Test
    void sectorSharesSumToOne() {
        Loan a = fixed("300", "0.05", "2026-01-01", "2027-01-01", 4, Amortization.BULLET, "100").withSector("Energy");
        Loan b = fixed("100", "0.05", "2026-01-01", "2027-01-01", 4, Amortization.BULLET, "100").withSector("Tech");
        Loan c = fixed("100", "0.05", "2026-01-01", "2027-01-01", 4, Amortization.BULLET, "100").withSector("Energy");
        Map<String, BigDecimal> m = Metrics.sectorConcentration(List.of(a, b, c));
        assertEquals(d("0.800000"), m.get("Energy"));
        assertEquals(d("0.200000"), m.get("Tech"));
        assertThrows(IllegalArgumentException.class, () -> Metrics.sectorConcentration(List.of()));
    }

    // ---- XIRR / yield / duration --------------------------------------------------------------------------------

    @Test
    void xirrMatchesKnownAnnualReturn() {
        // -1000 now, +1100 exactly 365 days later = 10%
        double r = Yield.xirr(List.of(new CashFlow(dt("2026-01-01"), d("-1000")), new CashFlow(dt("2027-01-01"), d("1100"))));
        assertEquals(0.10, r, 1e-9);
    }

    @Test
    void xirrHandlesMultipleFlowsAndMatchesNpvZero() {
        List<CashFlow> flows = List.of(new CashFlow(dt("2026-01-01"), d("-1000")), new CashFlow(dt("2026-07-01"), d("50")),
                new CashFlow(dt("2027-01-01"), d("1050")));
        double r = Yield.xirr(flows);
        double npv = 0;
        for (CashFlow f : flows) {
            double t = java.time.temporal.ChronoUnit.DAYS.between(dt("2026-01-01"), f.date()) / 365.0;
            npv += f.amount().doubleValue() / Math.pow(1 + r, t);
        }
        assertEquals(0.0, npv, 1e-6);
    }

    @Test
    void xirrRejectsFlowsWithoutASignChange() {
        assertThrows(IllegalArgumentException.class, () -> Yield.xirr(List.of(new CashFlow(dt("2026-01-01"), d("100")),
                new CashFlow(dt("2027-01-01"), d("100")))));
    }

    @Test
    void xirrFindsNegativeYieldWhenLenderLosesMoney() {
        double r = Yield.xirr(List.of(new CashFlow(dt("2026-01-01"), d("-1000")), new CashFlow(dt("2027-01-01"), d("900"))));
        assertEquals(-0.10, r, 1e-9);
    }

    @Test
    void parLoanYieldsRoughlyItsCoupon() {
        Loan l = fixed("1000000", "0.08", "2026-01-01", "2029-01-01", 1, Amortization.BULLET, "100");
        double y = Yield.yieldToMaturity(l, Schedule.build(l));
        assertEquals(0.08, y, 0.001);
    }

    @Test
    void discountPriceRaisesYieldAndPremiumLowersIt() {
        Loan par = fixed("1000000", "0.08", "2026-01-01", "2029-01-01", 4, Amortization.BULLET, "100");
        double atPar = Yield.yieldToMaturity(par, Schedule.build(par));
        double discount = Yield.yieldToMaturity(par.withPrice(d("95")), Schedule.build(par));
        double premium = Yield.yieldToMaturity(par.withPrice(d("105")), Schedule.build(par));
        assertTrue(discount > atPar && atPar > premium, discount + " > " + atPar + " > " + premium);
    }

    @Test
    void bulletMacaulayDurationIsBelowMaturityAndZeroCouponEqualsIt() {
        Loan l = fixed("1000000", "0.08", "2026-01-01", "2029-01-01", 1, Amortization.BULLET, "100");
        List<Payment> s = Schedule.build(l);
        double y = Yield.yieldToMaturity(l, s);
        double dur = Yield.macaulayDuration(l, s, y);
        assertTrue(dur < 3.0 && dur > 2.5, "duration " + dur);
        assertEquals(dur / (1 + y), Yield.modifiedDuration(l, s, y), 1e-12);
        Loan zero = fixed("1000000", "0", "2026-01-01", "2029-01-01", 1, Amortization.BULLET, "100");
        assertEquals(ChronoUnitYears.of(zero), Yield.macaulayDuration(zero, Schedule.build(zero), 0.05), 1e-9);
    }

    // ---- stress ---------------------------------------------------------------------------------------------------

    private static final Loan BULLET_1Y = fixed("1000000", "0.08", "2026-01-01", "2027-01-01", 1, Amortization.BULLET, "100");

    @Test
    void zeroDefaultScenarioReproducesScheduledCashFlowsAndNoLoss() {
        Stress.LoanResult r = Stress.run(BULLET_1Y, Schedule.build(BULLET_1Y), new Stress.Scenario("none", d("0"), d("0.5")));
        assertEquals(0, r.expectedLoss().signum());
        assertEquals(Yield.yieldToMaturity(BULLET_1Y, Schedule.build(BULLET_1Y)), r.expectedYield(), 1e-9);
    }

    @Test
    void expectedLossMatchesHandCalculationForOnePeriod() {
        // one annual period, CDR 10%, recovery 40%: loss = 10% * 60% * 1,000,000 = 60,000
        Stress.LoanResult r = Stress.run(BULLET_1Y, Schedule.build(BULLET_1Y), new Stress.Scenario("s", d("0.10"), d("0.40")));
        assertEquals(0, d("60000.00").compareTo(r.expectedLoss()), r.expectedLoss().toPlainString());
        assertEquals(d("0.060000"), r.lossRate());
        // expected cash at maturity: 0.9 * (1,000,000 + 80,000) + 0.1 * 0.4 * 1,000,000 = 1,012,000
        assertEquals(0, d("1012000.00").compareTo(r.expectedFlows().get(1).amount()), r.expectedFlows().get(1).amount().toPlainString());
    }

    @Test
    void fullRecoveryMeansNoLoss() {
        Stress.LoanResult r = Stress.run(BULLET_1Y, Schedule.build(BULLET_1Y), new Stress.Scenario("s", d("0.2"), d("1")));
        assertEquals(0, r.expectedLoss().signum());
    }

    @Test
    void harsherScenariosGiveHigherLossAndLowerYield() {
        Loan l = fixed("1000000", "0.08", "2026-01-01", "2031-01-01", 4, Amortization.STRAIGHT_LINE, "100");
        List<Payment> s = Schedule.build(l);
        Stress.LoanResult base = Stress.run(l, s, new Stress.Scenario("base", d("0.01"), d("0.65")));
        Stress.LoanResult bad = Stress.run(l, s, new Stress.Scenario("bad", d("0.08"), d("0.40")));
        assertTrue(bad.expectedLoss().compareTo(base.expectedLoss()) > 0);
        assertTrue(bad.expectedYield() < base.expectedYield());
    }

    @Test
    void portfolioLossIsSumOfLoansAndSplitsBySector() {
        Loan a = fixed("1000000", "0.08", "2026-01-01", "2027-01-01", 1, Amortization.BULLET, "100").withSector("Energy");
        Loan b = fixed("3000000", "0.08", "2026-01-01", "2027-01-01", 1, Amortization.BULLET, "100").withSector("Tech");
        Stress.PortfolioResult r = Stress.run(List.of(a, b), null, new Stress.Scenario("s", d("0.10"), d("0.40")));
        assertEquals(0, d("240000.00").compareTo(r.expectedLoss()), r.expectedLoss().toPlainString());
        assertEquals(0, d("60000.00").compareTo(r.lossBySector().get("Energy")));
        assertEquals(0, d("180000.00").compareTo(r.lossBySector().get("Tech")));
        assertEquals(d("0.060000"), r.lossRate());
    }

    @Test
    void scenarioInputIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> new Stress.Scenario("x", d("1"), d("0.5")));
        assertThrows(IllegalArgumentException.class, () -> new Stress.Scenario("x", d("0.1"), d("1.5")));
        assertThrows(IllegalArgumentException.class, () -> new Stress.Scenario("x", d("-0.1"), d("0.5")));
        var e = assertThrows(IllegalArgumentException.class, () -> Stress.Scenario.parse(List.of("h", "a,0.1,0.5", "b,zzz,0.5")));
        assertTrue(e.getMessage().startsWith("scenarios line 3"), e.getMessage());
    }

    /** Years from start to maturity on ACT/365, for the zero-coupon duration check. */
    private static final class ChronoUnitYears {
        static double of(Loan l) {
            return java.time.temporal.ChronoUnit.DAYS.between(l.start(), l.maturity()) / 365.0;
        }
    }
}
