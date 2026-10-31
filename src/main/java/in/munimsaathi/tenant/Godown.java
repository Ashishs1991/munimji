package in.munimsaathi.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "locations")
public class Godown {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    protected Godown() {
    }

    public Godown(UUID id, UUID businessId, String code, String name) {
        this.id = id;
        this.businessId = businessId;
        this.code = code;
        this.name = name;
        this.active = true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
