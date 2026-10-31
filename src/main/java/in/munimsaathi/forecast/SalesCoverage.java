package in.munimsaathi.forecast;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "sales_coverage")
public class SalesCoverage {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "covered_on", nullable = false)
    private LocalDate coveredOn;

    protected SalesCoverage() {
    }

    public SalesCoverage(UUID id, UUID businessId, LocalDate coveredOn) {
        this.id = id;
        this.businessId = businessId;
        this.coveredOn = coveredOn;
    }
}
