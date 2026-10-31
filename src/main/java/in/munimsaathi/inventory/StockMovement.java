package in.munimsaathi.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stock_movements")
public class StockMovement {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Ownership ownership;

    @Column(name = "signed_base_quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal signedBaseQuantity;

    @Column(name = "display_quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal displayQuantity;

    @Column(name = "unit_label", nullable = false)
    private String unitLabel;

    @Column(name = "factor_to_base", nullable = false, precision = 18, scale = 6)
    private BigDecimal factorToBase;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StockReason reason;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected StockMovement() {
    }

    public StockMovement(
            UUID id,
            UUID businessId,
            UUID productId,
            UUID batchId,
            UUID locationId,
            Ownership ownership,
            BigDecimal signedBaseQuantity,
            BigDecimal displayQuantity,
            String unitLabel,
            BigDecimal factorToBase,
            StockReason reason,
            UUID operationId,
            Instant occurredAt
    ) {
        this.id = id;
        this.businessId = businessId;
        this.productId = productId;
        this.batchId = batchId;
        this.locationId = locationId;
        this.ownership = ownership;
        this.signedBaseQuantity = signedBaseQuantity;
        this.displayQuantity = displayQuantity;
        this.unitLabel = unitLabel;
        this.factorToBase = factorToBase;
        this.reason = reason;
        this.operationId = operationId;
        this.occurredAt = occurredAt;
    }

    public BigDecimal getSignedBaseQuantity() {
        return signedBaseQuantity;
    }

    public BigDecimal getFactorToBase() {
        return factorToBase;
    }

    public StockReason getReason() {
        return reason;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public Ownership getOwnership() {
        return ownership;
    }
}
