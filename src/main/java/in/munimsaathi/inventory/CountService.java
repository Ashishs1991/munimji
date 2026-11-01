package in.munimsaathi.inventory;

import in.munimsaathi.catalog.CatalogService;
import in.munimsaathi.catalog.QuantityConversion;
import in.munimsaathi.tenant.Godown;
import in.munimsaathi.tenant.GodownRepository;
import in.munimsaathi.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class CountService {

    private final TenantContext tenants;
    private final GodownRepository godowns;
    private final CatalogService catalog;
    private final CountSessionRepository sessions;
    private final CountLineRepository lines;
    private final BatchRepository batches;
    private final PostedOperationRepository operations;
    private final StockBalanceRepository balances;
    private final StockPosting stock;

    public CountService(
            TenantContext tenants,
            GodownRepository godowns,
            CatalogService catalog,
            CountSessionRepository sessions,
            CountLineRepository lines,
            BatchRepository batches,
            PostedOperationRepository operations,
            StockBalanceRepository balances,
            StockPosting stock
    ) {
        this.tenants = tenants;
        this.godowns = godowns;
        this.catalog = catalog;
        this.sessions = sessions;
        this.lines = lines;
        this.batches = batches;
        this.operations = operations;
        this.balances = balances;
        this.stock = stock;
    }

    @Transactional
    public UUID start(UUID userId, UUID locationId, Instant effectiveAt, String scope) {
        if (effectiveAt == null || scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("A count needs an effective time and a scope.");
        }
        UUID businessId = tenants.currentBusinessId(userId);
        requireGodown(businessId, locationId);
        CountSession session = new CountSession(UUID.randomUUID(), businessId, locationId, effectiveAt, scope.trim());
        return sessions.save(session).getId();
    }

    @Transactional
    public void propose(UUID userId, UUID sessionId, UUID productId, BigDecimal displayQuantity, String unitLabel, LocalDate expiryDate) {
        UUID businessId = tenants.currentBusinessId(userId);
        CountSession session = requireOpenSession(businessId, sessionId);
        if (lines.existsBySessionIdAndProductId(sessionId, productId)) {
            throw new IllegalArgumentException("This product is already on the count.");
        }
        catalog.convertCount(userId, productId, displayQuantity, unitLabel);
        long baseline = baselineVersion(businessId, productId, session.getLocationId(), expiryDate);
        lines.save(new CountLine(
                UUID.randomUUID(),
                sessionId,
                productId,
                displayQuantity,
                unitLabel,
                baseline,
                expiryDate
        ));
    }

    @Transactional(readOnly = true)
    public CountProgress progress(UUID userId, UUID sessionId) {
        UUID businessId = tenants.currentBusinessId(userId);
        requireSession(businessId, sessionId);
        List<CountLine> sessionLines = lines.findBySessionIdOrderByProductIdAsc(sessionId);
        int confirmed = (int) sessionLines.stream().filter(line -> line.getConfirmedQuantity() != null).count();
        return new CountProgress(confirmed, sessionLines.size() - confirmed);
    }

    @Transactional
    public void confirm(UUID userId, UUID sessionId, UUID operationId) {
        if (operations.existsById(operationId)) {
            return;
        }
        UUID businessId = tenants.currentBusinessId(userId);
        CountSession session = requireSession(businessId, sessionId);
        if (session.getStatus() == CountStatus.CONFIRMED) {
            throw new AlreadyConfirmedException();
        }
        List<CountLine> sessionLines = lines.findBySessionIdOrderByProductIdAsc(sessionId);
        if (sessionLines.isEmpty()) {
            throw new IllegalArgumentException("Add at least one counted product before confirming.");
        }
        operations.saveAndFlush(new PostedOperation(operationId, businessId, "COUNT", userId));
        for (CountLine line : sessionLines) {
            UUID batchId = null;
            if (line.getExpiryPrecision() == ExpiryPrecision.DAY) {
                Batch batch = batches.findByProductIdAndExpiryDate(line.getProductId(), line.getExpiryDate())
                        .orElseGet(() -> batches.save(new Batch(
                                UUID.randomUUID(),
                                businessId,
                                line.getProductId(),
                                line.getExpiryDate()
                        )));
                batchId = batch.getId();
                line.attachBatch(batchId);
            }
            QuantityConversion conversion = catalog.convertCount(
                    userId,
                    line.getProductId(),
                    line.getDisplayQuantity(),
                    line.getUnitLabel()
            );
            stock.setCountedQuantity(new CountedStock(
                    businessId,
                    line.getProductId(),
                    batchId,
                    session.getLocationId(),
                    conversion.baseQuantity(),
                    conversion.displayQuantity(),
                    conversion.unitLabel(),
                    conversion.factorToBase(),
                    line.getBaselineVersion(),
                    operationId,
                    session.getEffectiveAt()
            ));
            line.confirm();
        }
        session.confirm(operationId);
    }

    @Transactional(readOnly = true)
    public BigDecimal balance(UUID userId, UUID productId, UUID locationId) {
        UUID businessId = tenants.currentBusinessId(userId);
        return stock.businessOwnedQuantity(businessId, productId, locationId);
    }

    private CountSession requireOpenSession(UUID businessId, UUID sessionId) {
        CountSession session = requireSession(businessId, sessionId);
        if (session.getStatus() != CountStatus.OPEN) {
            throw new AlreadyConfirmedException();
        }
        return session;
    }

    private CountSession requireSession(UUID businessId, UUID sessionId) {
        CountSession session = sessions.findById(sessionId).orElseThrow(() -> new IllegalArgumentException("Unknown count."));
        if (!session.getBusinessId().equals(businessId)) {
            throw new IllegalArgumentException("Unknown count.");
        }
        return session;
    }

    private long baselineVersion(UUID businessId, UUID productId, UUID locationId, LocalDate expiryDate) {
        if (expiryDate == null) {
            return balances.findByBusinessIdAndProductIdAndLocationIdAndOwnershipAndBatchIdIsNull(
                    businessId,
                    productId,
                    locationId,
                    Ownership.BUSINESS
            ).map(StockBalance::getVersion).orElse(0L);
        }
        return batches.findByProductIdAndExpiryDate(productId, expiryDate)
                .flatMap(batch -> balances.findByBusinessIdAndProductIdAndLocationIdAndOwnershipAndBatchId(
                        businessId,
                        productId,
                        locationId,
                        Ownership.BUSINESS,
                        batch.getId()
                ))
                .map(StockBalance::getVersion)
                .orElse(0L);
    }

    private void requireGodown(UUID businessId, UUID locationId) {
        Godown godown = godowns.findById(locationId).orElseThrow(() -> new IllegalArgumentException("Unknown godown."));
        if (!godown.getBusinessId().equals(businessId)) {
            throw new IllegalArgumentException("Unknown godown.");
        }
    }
}
