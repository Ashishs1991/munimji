package in.munimsaathi.dashboard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "low_stock_alerts")
public class LowStockAlert {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "threshold_quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal thresholdQuantity;

    @Column(name = "observed_quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal observedQuantity;

    @Column(name = "opened_on", nullable = false)
    private LocalDate openedOn;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected LowStockAlert() {
    }

    public LowStockAlert(
            UUID id,
            UUID businessId,
            UUID productId,
            BigDecimal thresholdQuantity,
            BigDecimal observedQuantity,
            LocalDate openedOn
    ) {
        this.id = id;
        this.businessId = businessId;
        this.productId = productId;
        this.thresholdQuantity = thresholdQuantity;
        this.observedQuantity = observedQuantity;
        this.openedOn = openedOn;
    }

    public UUID getProductId() {
        return productId;
    }

    public BigDecimal getThresholdQuantity() {
        return thresholdQuantity;
    }

    public BigDecimal getObservedQuantity() {
        return observedQuantity;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    void observe(BigDecimal thresholdQuantity, BigDecimal observedQuantity) {
        this.thresholdQuantity = thresholdQuantity;
        this.observedQuantity = observedQuantity;
    }

    void resolve(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
