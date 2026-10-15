package dev.manas.credit;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Payment(LocalDate date, BigDecimal interest, BigDecimal principal, BigDecimal balanceAfter) {
    public BigDecimal total() {
        return interest.add(principal);
    }
}
