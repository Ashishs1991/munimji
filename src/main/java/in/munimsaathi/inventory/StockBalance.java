package in.munimsaathi.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "stock_balances")
public class StockBalance {

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

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal quantity;

    @Column(nullable = false)
    private long version;

    protected StockBalance() {
    }

    public StockBalance(UUID id, UUID businessId, UUID productId, UUID batchId, UUID locationId, Ownership ownership) {
        this.id = id;
        this.businessId = businessId;
        this.productId = productId;
        this.batchId = batchId;
        this.locationId = locationId;
        this.ownership = ownership;
        this.quantity = BigDecimal.ZERO;
        this.version = 0;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public long getVersion() {
        return version;
    }

    public void apply(BigDecimal quantity) {
        this.quantity = quantity;
        this.version = this.version + 1;
    }
}
