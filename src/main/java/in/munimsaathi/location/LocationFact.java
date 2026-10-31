package in.munimsaathi.location;

import in.munimsaathi.inventory.Ownership;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "location_facts")
public class LocationFact {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(nullable = false)
    private String label;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Ownership ownership;

    @Column(precision = 18, scale = 6)
    private BigDecimal quantity;

    @Column(name = "noted_at", nullable = false)
    private Instant notedAt;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "operation_id", nullable = false)
    private UUID operationId;

    protected LocationFact() {
    }

    public LocationFact(
            UUID id,
            UUID businessId,
            String label,
            UUID productId,
            UUID locationId,
            Ownership ownership,
            BigDecimal quantity,
            Instant notedAt,
            UUID actorId,
            UUID operationId
    ) {
        this.id = id;
        this.businessId = businessId;
        this.label = label;
        this.productId = productId;
        this.locationId = locationId;
        this.ownership = ownership;
        this.quantity = quantity;
        this.notedAt = notedAt;
        this.actorId = actorId;
        this.operationId = operationId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }
}
