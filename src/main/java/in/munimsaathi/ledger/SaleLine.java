package in.munimsaathi.ledger;

import java.math.BigDecimal;
import java.util.UUID;

public record SaleLine(UUID productId, BigDecimal displayQuantity, String unitLabel, Long amountPaise) {
}
