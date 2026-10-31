package in.munimsaathi.catalog;

import java.math.BigDecimal;

public record QuantityConversion(
        BigDecimal displayQuantity,
        String unitLabel,
        BigDecimal factorToBase,
        BigDecimal baseQuantity
) {
}