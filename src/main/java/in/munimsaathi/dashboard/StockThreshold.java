package in.munimsaathi.dashboard;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "stock_thresholds")
public class StockThreshold {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "minimum_base_quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal minimumBaseQuantity;

    protected StockThreshold() {
    }

    public StockThreshold(UUID id, UUID businessId, UUID productId, BigDecimal minimumBaseQuantity) {
        this.id = id;
        this.businessId = businessId;
        this.productId = productId;
        this.minimumBaseQuantity = minimumBaseQuantity;
    }

    public UUID getProductId() {
        return productId;
    }

    public BigDecimal getMinimumBaseQuantity() {
        return minimumBaseQuantity;
    }

    void change(BigDecimal minimumBaseQuantity) {
        this.minimumBaseQuantity = minimumBaseQuantity;
    }
}
