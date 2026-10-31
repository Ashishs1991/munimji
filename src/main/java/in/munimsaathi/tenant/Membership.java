package in.munimsaathi.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "memberships")
public class Membership {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "auth_user_id", nullable = false)
    private UUID authUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MembershipRole role;

    protected Membership() {
    }

    public Membership(UUID id, UUID businessId, UUID authUserId, MembershipRole role) {
        this.id = id;
        this.businessId = businessId;
        this.authUserId = authUserId;
        this.role = role;
    }

    public UUID getBusinessId() {
        return businessId;
    }
}
