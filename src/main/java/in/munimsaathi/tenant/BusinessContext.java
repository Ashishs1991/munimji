package in.munimsaathi.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "business_contexts")
public class BusinessContext {

    @Id
    @Column(name = "auth_user_id")
    private UUID authUserId;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "context_version", nullable = false)
    private long contextVersion;

    @Column(name = "selected_at", nullable = false)
    private Instant selectedAt;

    protected BusinessContext() {
    }

    public BusinessContext(UUID authUserId) {
        this.authUserId = authUserId;
    }

    public void select(UUID businessId, Instant selectedAt) {
        this.businessId = businessId;
        this.contextVersion = this.contextVersion + 1;
        this.selectedAt = selectedAt;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public long getContextVersion() {
        return contextVersion;
    }
}
