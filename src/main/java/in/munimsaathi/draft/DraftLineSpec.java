package in.munimsaathi.draft;

import java.math.BigDecimal;
import java.util.UUID;

public record DraftLineSpec(
        UUID productId,
        BigDecimal displayQuantity,
        String unitLabel,
        Long amountPaise,
        String unresolved
) {
}
