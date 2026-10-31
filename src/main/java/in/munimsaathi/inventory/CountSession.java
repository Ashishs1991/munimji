package in.munimsaathi.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "count_sessions")
public class CountSession {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(nullable = false)
    private String scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CountStatus status;

    @Column(name = "confirming_operation_id")
    private UUID confirmingOperationId;

    protected CountSession() {
    }

    public CountSession(UUID id, UUID businessId, UUID locationId, Instant effectiveAt, String scope) {
        this.id = id;
        this.businessId = businessId;
        this.locationId = locationId;
        this.effectiveAt = effectiveAt;
        this.scope = scope;
        this.status = CountStatus.OPEN;
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

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public CountStatus getStatus() {
        return status;
    }

    public UUID getConfirmingOperationId() {
        return confirmingOperationId;
    }

    public void confirm(UUID operationId) {
        this.status = CountStatus.CONFIRMED;
        this.confirmingOperationId = operationId;
    }
}
