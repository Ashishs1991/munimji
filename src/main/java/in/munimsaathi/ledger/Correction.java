package in.munimsaathi.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "corrections")
public class Correction {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(name = "source_document_id", nullable = false)
    private UUID sourceDocumentId;

    @Column(name = "replacement_document_id", nullable = false)
    private UUID replacementDocumentId;

    @Column(nullable = false)
    private String reason;

    @Column(name = "approver_id", nullable = false)
    private UUID approverId;

    protected Correction() {
    }

    public Correction(
            UUID id,
            UUID businessId,
            UUID operationId,
            UUID sourceDocumentId,
            UUID replacementDocumentId,
            String reason,
            UUID approverId
    ) {
        this.id = id;
        this.businessId = businessId;
        this.operationId = operationId;
        this.sourceDocumentId = sourceDocumentId;
        this.replacementDocumentId = replacementDocumentId;
        this.reason = reason;
        this.approverId = approverId;
    }

    public UUID getSourceDocumentId() {
        return sourceDocumentId;
    }

    public UUID getReplacementDocumentId() {
        return replacementDocumentId;
    }

    public String getReason() {
        return reason;
    }

    public UUID getApproverId() {
        return approverId;
    }
}
