package in.munimsaathi.draft;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "approvals")
public class Approval {

    @Id
    private UUID id;

    @Column(name = "draft_id", nullable = false)
    private UUID draftId;

    @Column(name = "draft_version", nullable = false)
    private long draftVersion;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "approved_at", nullable = false)
    private Instant approvedAt;

    protected Approval() {
    }

    public Approval(UUID id, UUID draftId, long draftVersion, String contentHash, UUID actorId, Instant approvedAt) {
        this.id = id;
        this.draftId = draftId;
        this.draftVersion = draftVersion;
        this.contentHash = contentHash;
        this.actorId = actorId;
        this.approvedAt = approvedAt;
    }
}
