package in.munimsaathi.whatsapp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;
import java.time.Instant;

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

    @Column(name = "reminder_task_id")
    private UUID reminderTaskId;

    @Column(name = "draft_version")
    private Long draftVersion;

    @Column(name = "content_hash")
    private String contentHash;

    @Column(name = "reply_text")
    private String replyText;

    public String getReplyText() { return replyText; }
    public void rememberReply(String text) { replyText = text; }

    @Column(name = "reply_sent_at")
    private Instant replySentAt;

    public Instant getProposalShownAt() { return proposalShownAt; }

    @Column(name = "proposal_shown_at")
    private Instant proposalShownAt;

    public void rememberShownProposal(long version, String hash) {
        if (proposalShownAt == null) {
            proposalShownAt = Instant.now();
            draftVersion = version;
            contentHash = hash;
        }
    }

    @Column(name = "history_saved", nullable = false)
    private boolean historySaved;

    public boolean isHistorySaved() { return historySaved; }
    public void markHistorySaved() { historySaved = true; }
    public void useTranscript(String transcript) { body = transcript; }

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
        bindApproval(draftId, null, null);
    }

    public void bindApproval(UUID draftId, Long version, String contentHash) {
        this.approvalBound = true;
        this.draftId = draftId;
        this.draftVersion = version;
        this.contentHash = contentHash;
    }

    public void offerReminder(UUID taskId) { this.reminderTaskId = taskId; }

    public void bindReminder(UUID taskId) {
        this.approvalBound = true;
        this.reminderTaskId = taskId;
    }

    public void recordPosting(UUID documentId) {
        this.postedDocumentId = documentId;
    }

    public void recordAttempt(String replyStatus) {
        this.replyStatus = replyStatus;
        if ("accepted".equals(replyStatus)) {
            this.status = InboundStatus.REPLIED;
            replySentAt = Instant.now();
            if (proposalShownAt != null && !approvalBound) {
                proposalShownAt = replySentAt;
            }
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

    public UUID getReminderTaskId() {
        return reminderTaskId;
    }

    public Long getDraftVersion() {
        return draftVersion;
    }

    public String getContentHash() {
        return contentHash;
    }

    public UUID getBusinessId() {
        return businessId;
    }
}
