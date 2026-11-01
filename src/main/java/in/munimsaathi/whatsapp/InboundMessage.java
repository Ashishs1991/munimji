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

    @Column(name = "reply_status")
    private String replyStatus;

    @Column(name = "posted_document_id")
    private UUID postedDocumentId;

    @Column(name = "media_id")
    private String mediaId;

    @Column(name = "media_type")
    private String mediaType;

    @Column(name = "approval_bound", nullable = false)
    private boolean approvalBound;

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

    public void attachMedia(String mediaId, String mediaType) {
        this.mediaId = mediaId;
        this.mediaType = mediaType;
    }

    public void bindApproval(UUID draftId) {
        this.approvalBound = true;
        this.draftId = draftId;
    }

    public void recordPosting(UUID documentId) {
        this.postedDocumentId = documentId;
    }

    public void recordAttempt(String replyStatus) {
        this.replyStatus = replyStatus;
        if ("accepted".equals(replyStatus)) {
            this.status = InboundStatus.REPLIED;
        }
    }

    public void markReplied() {
        this.status = InboundStatus.REPLIED;
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

    public String getPhone() {
        return phone;
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

    public String getReplyStatus() {
        return replyStatus;
    }

    public UUID getPostedDocumentId() {
        return postedDocumentId;
    }

    public String getMediaId() {
        return mediaId;
    }

    public String getMediaType() {
        return mediaType;
    }

    public boolean isApprovalBound() {
        return approvalBound;
    }

    public UUID getBusinessId() {
        return businessId;
    }
}
