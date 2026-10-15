package dev.manas.credit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * A reference-rate curve (e.g. projected SOFR) as a step function: the rate on a date is the latest fixing on or before it.
 */
public final class RateCurve {
    private final NavigableMap<LocalDate, BigDecimal> fixings;

    private RateCurve(NavigableMap<LocalDate, BigDecimal> fixings) {
        if (fixings.isEmpty()) throw new IllegalArgumentException("rate curve has no fixings");
        fixings.forEach((d, r) -> {
            if (r == null || r.signum() < 0) throw new IllegalArgumentException("negative or missing rate on " + d);
        });
        this.fixings = fixings;
    }

    public static RateCurve of(Map<LocalDate, BigDecimal> fixings) {
        return new RateCurve(new TreeMap<>(fixings));
    }

    public static RateCurve flat(BigDecimal rate) {
        return of(Map.of(LocalDate.MIN, rate));
    }

    /** Parses {@code date,rate} lines (first line is a header). */
    public static RateCurve parse(List<String> lines) {
        TreeMap<LocalDate, BigDecimal> m = new TreeMap<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            String[] f = lines.get(i).split(",", -1);
            try {
                if (f.length != 2) throw new IllegalArgumentException("expected 2 columns, got " + f.length);
                LocalDate d = LocalDate.parse(f[0].trim());
                if (m.put(d, new BigDecimal(f[1].trim())) != null) throw new IllegalArgumentException("duplicate date " + d);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("curve line " + (i + 1) + ": " + e.getMessage());
            }
        }
        return new RateCurve(m);
    }

    public BigDecimal rateOn(LocalDate date) {
        Map.Entry<LocalDate, BigDecimal> e = fixings.floorEntry(date);
        if (e == null) throw new IllegalArgumentException("no rate fixing on or before " + date);
        return e.getValue();
    }
}
