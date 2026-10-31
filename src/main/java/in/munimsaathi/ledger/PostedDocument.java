package in.munimsaathi.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "documents")
public class PostedDocument {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "party_id")
    private UUID partyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentKind kind;

    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    @Column(name = "total_paise")
    private Long totalPaise;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "finance_status", nullable = false)
    private FinanceStatus financeStatus;

    @Column(name = "bill_number")
    private String billNumber;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    private String fingerprint;

    protected PostedDocument() {
    }

    public PostedDocument(
            UUID id,
            UUID businessId,
            UUID partyId,
            DocumentKind kind,
            LocalDate occurredOn,
            Long totalPaise,
            UUID operationId,
            UUID sourceDocumentId,
            FinanceStatus financeStatus
    ) {
        this.id = id;
        this.businessId = businessId;
        this.partyId = partyId;
        this.kind = kind;
        this.occurredOn = occurredOn;
        this.totalPaise = totalPaise;
        this.operationId = operationId;
        this.sourceDocumentId = sourceDocumentId;
        this.financeStatus = financeStatus;
    }

    void describeBill(String billNumber, LocalDate periodStart, LocalDate periodEnd, String fingerprint) {
        this.billNumber = billNumber;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.fingerprint = fingerprint;
    }

    void markFinance(FinanceStatus financeStatus) {
        this.financeStatus = financeStatus;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public UUID getPartyId() {
        return partyId;
    }

    public DocumentKind getKind() {
        return kind;
    }

    public Long getTotalPaise() {
        return totalPaise;
    }

    public FinanceStatus getFinanceStatus() {
        return financeStatus;
    }

    public String getBillNumber() {
        return billNumber;
    }

    public String getFingerprint() {
        return fingerprint;
    }
}
