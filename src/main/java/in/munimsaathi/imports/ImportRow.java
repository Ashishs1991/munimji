package in.munimsaathi.imports;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "import_rows")
public class ImportRow {

    @Id
    private UUID id;

    @Column(name = "import_id", nullable = false)
    private UUID importId;

    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @Column(name = "row_class", nullable = false)
    private String rowClass;

    @Column(name = "source_id")
    private String sourceId;

    @Column
    private String name;

    @Column(precision = 18, scale = 6)
    private BigDecimal quantity;

    @Column(name = "amount_paise")
    private Long amountPaise;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "party_id")
    private UUID partyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ImportRowStatus status;

    @Column
    private String error;

    protected ImportRow() {
    }

    public ImportRow(
            UUID id,
            UUID importId,
            int rowNumber,
            String rowClass,
            String sourceId,
            String name,
            BigDecimal quantity,
            Long amountPaise,
            UUID productId,
            UUID partyId,
            ImportRowStatus status,
            String error
    ) {
        this.id = id;
        this.importId = importId;
        this.rowNumber = rowNumber;
        this.rowClass = rowClass;
        this.sourceId = sourceId;
        this.name = name;
        this.quantity = quantity;
        this.amountPaise = amountPaise;
        this.productId = productId;
        this.partyId = partyId;
        this.status = status;
        this.error = error;
    }

    public String getRowClass() {
        return rowClass;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public Long getAmountPaise() {
        return amountPaise;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getPartyId() {
        return partyId;
    }

    public ImportRowStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getSourceId() {
        return sourceId;
    }
}
