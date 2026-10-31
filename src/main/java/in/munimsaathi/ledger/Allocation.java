package in.munimsaathi.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "allocations")
public class Allocation {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "source_entry_id", nullable = false)
    private UUID sourceEntryId;

    @Column(name = "target_document_id", nullable = false)
    private UUID targetDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_side", nullable = false)
    private AccountSide accountSide;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    protected Allocation() {
    }

    public Allocation(
            UUID id,
            UUID businessId,
            UUID sourceEntryId,
            UUID targetDocumentId,
            AccountSide accountSide,
            long amountPaise
    ) {
        this.id = id;
        this.businessId = businessId;
        this.sourceEntryId = sourceEntryId;
        this.targetDocumentId = targetDocumentId;
        this.accountSide = accountSide;
        this.amountPaise = amountPaise;
    }

    public long getAmountPaise() {
        return amountPaise;
    }
}
