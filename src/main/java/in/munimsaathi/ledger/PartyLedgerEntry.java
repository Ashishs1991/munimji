package in.munimsaathi.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "party_ledger_entries")
public class PartyLedgerEntry {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "party_id", nullable = false)
    private UUID partyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_side", nullable = false)
    private AccountSide accountSide;

    @Column(name = "signed_paise", nullable = false)
    private long signedPaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LedgerReason reason;

    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    protected PartyLedgerEntry() {
    }

    public PartyLedgerEntry(
            UUID id,
            UUID businessId,
            UUID partyId,
            AccountSide accountSide,
            long signedPaise,
            LedgerReason reason,
            UUID sourceDocumentId,
            UUID operationId,
            Instant effectiveAt
    ) {
        this.id = id;
        this.businessId = businessId;
        this.partyId = partyId;
        this.accountSide = accountSide;
        this.signedPaise = signedPaise;
        this.reason = reason;
        this.sourceDocumentId = sourceDocumentId;
        this.operationId = operationId;
        this.effectiveAt = effectiveAt;
    }

    public UUID getId() {
        return id;
    }

    public long getSignedPaise() {
        return signedPaise;
    }
}
