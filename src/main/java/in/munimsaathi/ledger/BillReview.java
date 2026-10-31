package in.munimsaathi.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "bill_reviews")
public class BillReview {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "bill_number", nullable = false)
    private String billNumber;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(nullable = false)
    private String fingerprint;

    @Column(nullable = false)
    private String status;

    protected BillReview() {
    }

    public BillReview(
            UUID id,
            UUID businessId,
            UUID supplierId,
            String billNumber,
            LocalDate periodStart,
            LocalDate periodEnd,
            String fingerprint
    ) {
        this.id = id;
        this.businessId = businessId;
        this.supplierId = supplierId;
        this.billNumber = billNumber;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.fingerprint = fingerprint;
        this.status = "NEEDS_REVIEW";
    }

    public UUID getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }
}
