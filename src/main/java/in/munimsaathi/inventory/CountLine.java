package in.munimsaathi.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "count_lines")
public class CountLine {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "display_quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal displayQuantity;

    @Column(name = "unit_label", nullable = false)
    private String unitLabel;

    @Column(name = "confirmed_quantity", precision = 18, scale = 6)
    private BigDecimal confirmedQuantity;

    @Column(name = "baseline_version", nullable = false)
    private long baselineVersion;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "expiry_precision", nullable = false)
    private ExpiryPrecision expiryPrecision;

    protected CountLine() {
    }

    public CountLine(
            UUID id,
            UUID sessionId,
            UUID productId,
            BigDecimal displayQuantity,
            String unitLabel,
            long baselineVersion,
            LocalDate expiryDate
    ) {
        this.id = id;
        this.sessionId = sessionId;
        this.productId = productId;
        this.displayQuantity = displayQuantity;
        this.unitLabel = unitLabel;
        this.baselineVersion = baselineVersion;
        this.expiryDate = expiryDate;
        this.expiryPrecision = expiryDate == null ? ExpiryPrecision.UNKNOWN : ExpiryPrecision.DAY;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public BigDecimal getDisplayQuantity() {
        return displayQuantity;
    }

    public String getUnitLabel() {
        return unitLabel;
    }

    public BigDecimal getConfirmedQuantity() {
        return confirmedQuantity;
    }

    public long getBaselineVersion() {
        return baselineVersion;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public ExpiryPrecision getExpiryPrecision() {
        return expiryPrecision;
    }

    public void attachBatch(UUID batchId) {
        this.batchId = batchId;
    }

    public void confirm() {
        this.confirmedQuantity = this.displayQuantity;
    }
}
