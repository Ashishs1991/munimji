package in.munimsaathi.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "batches")
public class Batch {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "batch_label")
    private String batchLabel;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "expiry_precision", nullable = false)
    private ExpiryPrecision expiryPrecision;

    protected Batch() {
    }

    public Batch(UUID id, UUID businessId, UUID productId, LocalDate expiryDate) {
        this.id = id;
        this.businessId = businessId;
        this.productId = productId;
        this.expiryDate = expiryDate;
        this.expiryPrecision = ExpiryPrecision.DAY;
    }

    public UUID getId() {
        return id;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }
}
