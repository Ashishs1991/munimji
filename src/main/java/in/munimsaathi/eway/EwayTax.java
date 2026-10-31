package in.munimsaathi.eway;

import java.math.BigDecimal;

public record EwayTax(
        Long taxablePaise,
        Long cgstPaise,
        Long sgstPaise,
        Long igstPaise,
        Long cessPaise,
        BigDecimal cgstRate,
        BigDecimal sgstRate,
        BigDecimal igstRate,
        BigDecimal cessRate
) {
}
