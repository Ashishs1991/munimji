package in.munimsaathi.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "invoice_counters")
public class InvoiceCounter {

    @Id
    @Column(name = "business_id")
    private UUID businessId;

    @Column(name = "last_number", nullable = false)
    private long lastNumber;

    protected InvoiceCounter() {
    }

    public InvoiceCounter(UUID businessId) {
        this.businessId = businessId;
        this.lastNumber = 0;
    }

    public long issueNext() {
        this.lastNumber = Math.incrementExact(this.lastNumber);
        return lastNumber;
    }
}
