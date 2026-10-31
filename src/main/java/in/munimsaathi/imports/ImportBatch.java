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
@Table(name = "imports")
public class ImportBatch {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_hash", nullable = false)
    private String fileHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ImportStatus status;

    @Column(name = "opening_pieces", nullable = false, precision = 18, scale = 6)
    private BigDecimal openingPieces;

    @Column(name = "opening_receivable_paise", nullable = false)
    private long openingReceivablePaise;

    @Column(name = "historical_sales", nullable = false)
    private int historicalSales;

    @Column(name = "quarantined_rows", nullable = false)
    private int quarantinedRows;

    protected ImportBatch() {
    }

    public ImportBatch(
            UUID id,
            UUID businessId,
            String fileName,
            String fileHash,
            BigDecimal openingPieces,
            long openingReceivablePaise,
            int historicalSales,
            int quarantinedRows
    ) {
        this.id = id;
        this.businessId = businessId;
        this.fileName = fileName;
        this.fileHash = fileHash;
        this.status = ImportStatus.STAGED;
        this.openingPieces = openingPieces;
        this.openingReceivablePaise = openingReceivablePaise;
        this.historicalSales = historicalSales;
        this.quarantinedRows = quarantinedRows;
    }

    public void activate() {
        this.status = ImportStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public ImportStatus getStatus() {
        return status;
    }

    public BigDecimal getOpeningPieces() {
        return openingPieces;
    }

    public long getOpeningReceivablePaise() {
        return openingReceivablePaise;
    }

    public int getHistoricalSales() {
        return historicalSales;
    }

    public int getQuarantinedRows() {
        return quarantinedRows;
    }

    public String getFileHash() {
        return fileHash;
    }
}
