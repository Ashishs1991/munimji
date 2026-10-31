package in.munimsaathi.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class StockPosting {

    private final StockBalanceRepository balances;
    private final StockMovementRepository movements;

    public StockPosting(StockBalanceRepository balances, StockMovementRepository movements) {
        this.balances = balances;
        this.movements = movements;
    }

    @Transactional
    public void setCountedQuantity(CountedStock counted) {
        Optional<StockBalance> existing = lock(counted);
        long version = existing.map(StockBalance::getVersion).orElse(0L);
        if (version != counted.expectedVersion()) {
            throw new StaleCountException();
        }
        BigDecimal current = existing.map(StockBalance::getQuantity).orElse(BigDecimal.ZERO);
        boolean opening = existing.isEmpty();
        BigDecimal delta = opening ? counted.confirmedBaseQuantity() : counted.confirmedBaseQuantity().subtract(current);
        StockReason reason = opening ? StockReason.OPENING : StockReason.ADJUSTMENT;
        write(counted.businessId(), counted.productId(), counted.batchId(), counted.locationId(), delta,
                counted.displayQuantity(), counted.unitLabel(), counted.factorToBase(), reason,
                counted.operationId(), counted.occurredAt(), existing);
    }

    @Transactional
    public void add(SignedStockChange change) {
        Optional<StockBalance> existing = lock(change.businessId(), change.productId(), change.locationId(), change.batchId());
        write(change.businessId(), change.productId(), change.batchId(), change.locationId(), change.signedBaseQuantity(),
                change.displayQuantity(), change.unitLabel(), change.factorToBase(), change.reason(),
                change.operationId(), change.occurredAt(), existing);
    }

    @Transactional(readOnly = true)
    public BigDecimal businessOwnedQuantity(UUID businessId, UUID productId, UUID locationId) {
        return balances.findByBusinessIdAndProductIdAndLocationIdAndOwnershipAndBatchIdIsNull(
                businessId,
                productId,
                locationId,
                Ownership.BUSINESS
        ).map(StockBalance::getQuantity).orElse(BigDecimal.ZERO);
    }

    private Optional<StockBalance> lock(CountedStock counted) {
        return lock(counted.businessId(), counted.productId(), counted.locationId(), counted.batchId());
    }

    private Optional<StockBalance> lock(UUID businessId, UUID productId, UUID locationId, UUID batchId) {
        if (batchId == null) {
            return balances.lockWithoutBatch(businessId, productId, locationId, Ownership.BUSINESS);
        }
        return balances.lockBatch(businessId, productId, locationId, Ownership.BUSINESS, batchId);
    }

    private void write(
            UUID businessId,
            UUID productId,
            UUID batchId,
            UUID locationId,
            BigDecimal signedBase,
            BigDecimal displayQuantity,
            String unitLabel,
            BigDecimal factorToBase,
            StockReason reason,
            UUID operationId,
            Instant occurredAt,
            Optional<StockBalance> existing
    ) {
        BigDecimal current = existing.map(StockBalance::getQuantity).orElse(BigDecimal.ZERO);
        BigDecimal next = current.add(signedBase);
        if (next.compareTo(BigDecimal.ZERO) < 0) {
            throw new InsufficientStockException();
        }
        movements.save(new StockMovement(
                UUID.randomUUID(),
                businessId,
                productId,
                batchId,
                locationId,
                Ownership.BUSINESS,
                signedBase,
                displayQuantity,
                unitLabel,
                factorToBase,
                reason,
                operationId,
                occurredAt
        ));
        StockBalance balance = existing.orElseGet(() -> new StockBalance(
                UUID.randomUUID(),
                businessId,
                productId,
                batchId,
                locationId,
                Ownership.BUSINESS
        ));
        balance.apply(next);
        balances.save(balance);
    }
}
