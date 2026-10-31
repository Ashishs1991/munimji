package in.munimsaathi.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "invoice_numbers")
public class InvoiceNumber {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(nullable = false)
    private long number;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceNumberStatus status;

    protected InvoiceNumber() {
    }

    public InvoiceNumber(UUID id, UUID businessId, long number, UUID documentId) {
        this.id = id;
        this.businessId = businessId;
        this.number = number;
        this.documentId = documentId;
        this.status = InvoiceNumberStatus.ISSUED;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public long getNumber() {
        return number;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public InvoiceNumberStatus getStatus() {
        return status;
    }

    void cancel() {
        this.status = InvoiceNumberStatus.CANCELED;
    }
}
