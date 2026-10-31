package in.munimsaathi.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "product_units")
public class ProductUnit {

    @Id
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "unit_label", nullable = false)
    private String unitLabel;

    @Column(name = "factor_to_base", nullable = false, precision = 18, scale = 6)
    private BigDecimal factorToBase;

    @Column(name = "approved_by", nullable = false)
    private UUID approvedBy;

    protected ProductUnit() {
    }

    public ProductUnit(UUID id, UUID productId, String unitLabel, BigDecimal factorToBase, UUID approvedBy) {
        this.id = id;
        this.productId = productId;
        this.unitLabel = unitLabel;
        this.factorToBase = factorToBase;
        this.approvedBy = approvedBy;
    }

    public String getUnitLabel() {
        return unitLabel;
    }

    public BigDecimal getFactorToBase() {
        return factorToBase;
    }
}
