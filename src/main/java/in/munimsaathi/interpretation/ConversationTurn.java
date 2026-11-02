package in.munimsaathi.interpretation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "conversation_turns")
public class ConversationTurn {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ConversationTurn() {
    }

    public ConversationTurn(UUID id, UUID businessId, UUID actorId, String role, String body, Instant createdAt) {
        this.id = id;
        this.businessId = businessId;
        this.actorId = actorId;
        this.role = role;
        this.body = body;
        this.createdAt = createdAt;
    }

    public String getRole() {
        return role;
    }

    public String getBody() {
        return body;
    }
}
