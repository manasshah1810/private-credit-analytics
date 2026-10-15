package dev.manas.credit;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Day-count conventions used to turn a date range into a fraction of a year. */
public enum DayCount {
    ACT_360 {
        @Override
        public BigDecimal yearFraction(LocalDate from, LocalDate to) {
            return frac(ChronoUnit.DAYS.between(from, to), 360);
        }
    },
    ACT_365 {
        @Override
        public BigDecimal yearFraction(LocalDate from, LocalDate to) {
            return frac(ChronoUnit.DAYS.between(from, to), 365);
        }
    },
    /** 30/360 US (bond basis). */
    THIRTY_360 {
        @Override
        public BigDecimal yearFraction(LocalDate from, LocalDate to) {
            int d1 = Math.min(from.getDayOfMonth(), 30);
            int d2 = to.getDayOfMonth();
            if (d2 == 31 && d1 == 30) d2 = 30;
            long days = 360L * (to.getYear() - from.getYear())
                    + 30L * (to.getMonthValue() - from.getMonthValue())
                    + (d2 - d1);
            return frac(days, 360);
        }
    };

    public abstract BigDecimal yearFraction(LocalDate from, LocalDate to);

    private static BigDecimal frac(long days, int basis) {
        return BigDecimal.valueOf(days).divide(BigDecimal.valueOf(basis), MathContext.DECIMAL64);
    }
}
