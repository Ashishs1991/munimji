package in.munimsaathi.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "document_lines")
public class DocumentLine {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "display_quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal displayQuantity;

    @Column(name = "unit_label", nullable = false)
    private String unitLabel;

    @Column(name = "factor_to_base", nullable = false, precision = 18, scale = 6)
    private BigDecimal factorToBase;

    @Column(name = "base_quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal baseQuantity;

    @Column(name = "amount_paise")
    private Long amountPaise;

    @Column(nullable = false)
    private boolean restock;

    protected DocumentLine() {
    }

    public DocumentLine(
            UUID id,
            UUID documentId,
            UUID productId,
            UUID locationId,
            BigDecimal displayQuantity,
            String unitLabel,
            BigDecimal factorToBase,
            BigDecimal baseQuantity,
            Long amountPaise,
            boolean restock
    ) {
        this.id = id;
        this.documentId = documentId;
        this.productId = productId;
        this.locationId = locationId;
        this.displayQuantity = displayQuantity;
        this.unitLabel = unitLabel;
        this.factorToBase = factorToBase;
        this.baseQuantity = baseQuantity;
        this.amountPaise = amountPaise;
        this.restock = restock;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public BigDecimal getDisplayQuantity() {
        return displayQuantity;
    }

    public BigDecimal getBaseQuantity() {
        return baseQuantity;
    }
}
