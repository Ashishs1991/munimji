package in.munimsaathi.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "parties")
public class Party {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PartyRole role;

    private String gstin;

    protected Party() {
    }

    public Party(UUID id, UUID businessId, String displayName, String normalizedName, PartyRole role, String gstin) {
        this.id = id;
        this.businessId = businessId;
        this.displayName = displayName;
        this.normalizedName = normalizedName;
        this.role = role;
        this.gstin = gstin;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public PartyRole getRole() {
        return role;
    }

    public void changeRole(PartyRole role) {
        this.role = role;
    }
}
