package in.munimsaathi.draft;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "draft_lines")
public class DraftLine {

    @Id
    private UUID id;

    @Column(name = "draft_id", nullable = false)
    private UUID draftId;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "display_quantity", precision = 18, scale = 6)
    private BigDecimal displayQuantity;

    @Column(name = "unit_label")
    private String unitLabel;

    @Column(name = "amount_paise")
    private Long amountPaise;

    private String unresolved;

    protected DraftLine() {
    }

    public DraftLine(
            UUID id,
            UUID draftId,
            int lineNumber,
            UUID productId,
            BigDecimal displayQuantity,
            String unitLabel,
            Long amountPaise,
            String unresolved
    ) {
        this.id = id;
        this.draftId = draftId;
        this.lineNumber = lineNumber;
        this.productId = productId;
        this.displayQuantity = displayQuantity;
        this.unitLabel = unitLabel;
        this.amountPaise = amountPaise;
        this.unresolved = unresolved;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public UUID getProductId() {
        return productId;
    }

    public BigDecimal getDisplayQuantity() {
        return displayQuantity;
    }

    public String getUnitLabel() {
        return unitLabel;
    }

    public Long getAmountPaise() {
        return amountPaise;
    }

    public String getUnresolved() {
        return unresolved;
    }
}
