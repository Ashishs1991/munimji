package in.munimsaathi.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "posted_operations")
public class PostedOperation {

    @Id
    @Column(name = "operation_id")
    private UUID operationId;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(nullable = false)
    private String kind;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    protected PostedOperation() {
    }

    public PostedOperation(UUID operationId, UUID businessId, String kind, UUID actorId) {
        this.operationId = operationId;
        this.businessId = businessId;
        this.kind = kind;
        this.actorId = actorId;
    }
}
