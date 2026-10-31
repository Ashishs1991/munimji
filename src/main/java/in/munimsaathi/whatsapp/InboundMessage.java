package in.munimsaathi.whatsapp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "inbound_messages")
public class InboundMessage {

    @Id
    private UUID id;

    @Column(name = "business_id")
    private UUID businessId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Column
    private String phone;

    @Column
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InboundStatus status;

    @Column(name = "draft_id")
    private UUID draftId;

    protected InboundMessage() {
    }

    public InboundMessage(
            UUID id,
            UUID businessId,
            UUID userId,
            String messageId,
            String phone,
            String body,
            InboundStatus status
    ) {
        this.id = id;
        this.businessId = businessId;
        this.userId = userId;
        this.messageId = messageId;
        this.phone = phone;
        this.body = body;
        this.status = status;
    }

    public void attachDraft(UUID draftId) {
        this.draftId = draftId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getBody() {
        return body;
    }

    public InboundStatus getStatus() {
        return status;
    }

    public UUID getDraftId() {
        return draftId;
    }

    public UUID getBusinessId() {
        return businessId;
    }
}
