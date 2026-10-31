package in.munimsaathi.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SignedStockChange(
        UUID businessId,
        UUID productId,
        UUID batchId,
        UUID locationId,
        BigDecimal signedBaseQuantity,
        BigDecimal displayQuantity,
        String unitLabel,
        BigDecimal factorToBase,
        StockReason reason,
        UUID operationId,
        Instant occurredAt
) {
}
