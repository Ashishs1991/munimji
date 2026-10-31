package in.munimsaathi.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CountedStock(
        UUID businessId,
        UUID productId,
        UUID batchId,
        UUID locationId,
        BigDecimal confirmedBaseQuantity,
        BigDecimal displayQuantity,
        String unitLabel,
        BigDecimal factorToBase,
        long expectedVersion,
        UUID operationId,
        Instant occurredAt
) {
}
