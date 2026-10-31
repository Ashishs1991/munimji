package in.munimsaathi.eway;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "eway_drafts")
public class EwayDraft {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EwayStatus status;

    @Column(nullable = false)
    private String environment;

    @Column(nullable = false)
    private String payload;

    @Column(name = "payload_hash", nullable = false)
    private String payloadHash;

    @Column(name = "approved_hash")
    private String approvedHash;

    @Column(nullable = false)
    private int version;

    @Column(name = "invoice_number")
    private Long invoiceNumber;

    @Column(name = "invoice_total_paise")
    private Long invoiceTotalPaise;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(name = "eway_bill_no")
    private String ewayBillNo;

    @Column(name = "eway_bill_date")
    private String ewayBillDate;

    @Column(name = "valid_upto")
    private String validUpto;

    @Column(name = "provider_status")
    private String providerStatus;

    @Column(name = "error_text")
    private String errorText;

    protected EwayDraft() {
    }

    public EwayDraft(UUID id, UUID businessId, UUID documentId, UUID actorId, String environment, UUID operationId) {
        this.id = id;
        this.businessId = businessId;
        this.documentId = documentId;
        this.actorId = actorId;
        this.environment = environment;
        this.operationId = operationId;
        this.status = EwayStatus.VALIDATION_BLOCKED;
        this.version = 1;
        this.payload = "{}";
        this.payloadHash = "";
    }

    void replace(
            String payload,
            String payloadHash,
            String errorText,
            boolean ready,
            Long invoiceNumber,
            Long invoiceTotalPaise,
            LocalDate invoiceDate
    ) {
        boolean first = this.payloadHash == null || this.payloadHash.isEmpty();
        boolean edited = !first && !this.payloadHash.equals(payloadHash);
        this.payload = payload;
        this.errorText = errorText;
        this.invoiceNumber = invoiceNumber;
        this.invoiceTotalPaise = invoiceTotalPaise;
        this.invoiceDate = invoiceDate;
        if (first || edited) {
            if (edited) {
                this.version = this.version + 1;
                this.approvedHash = null;
            }
            this.payloadHash = payloadHash;
            this.status = ready ? EwayStatus.AWAITING_APPROVAL : EwayStatus.VALIDATION_BLOCKED;
        }
    }

    void queue() {
        this.status = EwayStatus.QUEUED;
        this.approvedHash = this.payloadHash;
    }

    void markSubmitting() {
        this.status = EwayStatus.SUBMITTING;
    }

    void requeue(String errorText) {
        this.status = EwayStatus.QUEUED;
        this.errorText = errorText;
        this.providerStatus = "0";
    }

    void confirm(String ewayBillNo, String ewayBillDate, String validUpto, String providerStatus) {
        this.status = EwayStatus.CONFIRMED;
        this.ewayBillNo = ewayBillNo;
        this.ewayBillDate = ewayBillDate;
        this.validUpto = validUpto;
        this.providerStatus = providerStatus;
        this.errorText = null;
    }

    void reject(String errorText) {
        this.status = EwayStatus.REJECTED;
        this.providerStatus = "0";
        this.errorText = errorText;
    }

    void unknown(String errorText, String providerStatus) {
        this.status = EwayStatus.OUTCOME_UNKNOWN;
        this.errorText = errorText;
        this.providerStatus = providerStatus;
    }

    boolean approvalMatches() {
        return approvedHash != null && approvedHash.equals(payloadHash);
    }

    public UUID getId() {
        return id;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public EwayStatus getStatus() {
        return status;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getPayload() {
        return payload;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public int getVersion() {
        return version;
    }

    public Long getInvoiceNumber() {
        return invoiceNumber;
    }

    public Long getInvoiceTotalPaise() {
        return invoiceTotalPaise;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public UUID getOperationId() {
        return operationId;
    }

    public String getEwayBillNo() {
        return ewayBillNo;
    }

    public String getEwayBillDate() {
        return ewayBillDate;
    }

    public String getValidUpto() {
        return validUpto;
    }

    public String getErrorText() {
        return errorText;
    }
}
