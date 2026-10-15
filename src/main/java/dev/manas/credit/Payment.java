package dev.manas.credit;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One period of a loan schedule. {@code interest} is paid in cash; {@code pikInterest} is capitalised into the balance.
 * {@code couponRate} is the annual cash rate that applied to the period.
 */
public record Payment(LocalDate date, BigDecimal couponRate, BigDecimal interest, BigDecimal pikInterest,
                      BigDecimal principal, BigDecimal balanceAfter) {
    /** Cash received by the lender this period. */
    public BigDecimal total() {
        return interest.add(principal);
    }
}
