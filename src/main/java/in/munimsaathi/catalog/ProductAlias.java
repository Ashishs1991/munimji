package in.munimsaathi.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "product_aliases")
public class ProductAlias {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "normalized_alias", nullable = false)
    private String normalizedAlias;

    @Column(name = "supplier_party_id")
    private UUID supplierPartyId;

    protected ProductAlias() {
    }

    public ProductAlias(UUID id, UUID businessId, UUID productId, String normalizedAlias, UUID supplierPartyId) {
        this.id = id;
        this.businessId = businessId;
        this.productId = productId;
        this.normalizedAlias = normalizedAlias;
        this.supplierPartyId = supplierPartyId;
    }

    public UUID getProductId() {
        return productId;
    }
}
