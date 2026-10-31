package in.munimsaathi.reminder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "reminder_tasks")
public class ReminderTask {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReminderKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReminderStatus status;

    @Column(name = "party_id")
    private UUID partyId;

    @Column(name = "document_id")
    private UUID documentId;

    @Column
    private String contact;

    @Column(name = "note_only", nullable = false)
    private boolean noteOnly;

    @Column(name = "approved_amount_paise")
    private Long approvedAmountPaise;

    @Column(name = "approved_payload")
    private String approvedPayload;

    @Column(name = "schedule_version", nullable = false)
    private int scheduleVersion;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "local_date")
    private LocalDate localDate;

    @Column(name = "local_time")
    private LocalTime localTime;

    @Column(nullable = false)
    private String timezone;

    @Column(nullable = false)
    private boolean consent;

    @Column(name = "template_name")
    private String templateName;

    @Column(name = "send_key")
    private String sendKey;

    @Column(name = "provider_status")
    private String providerStatus;

    @Column(name = "provider_id")
    private String providerId;

    @Column
    private String outcome;

    protected ReminderTask() {
    }

    public ReminderTask(
            UUID id,
            UUID businessId,
            UUID actorId,
            ReminderKind kind,
            UUID partyId,
            UUID documentId,
            String contact,
            boolean noteOnly,
            String payload,
            Instant scheduledAt,
            LocalDate localDate,
            LocalTime localTime,
            String timezone,
            boolean consent,
            String templateName
    ) {
        this.id = id;
        this.businessId = businessId;
        this.actorId = actorId;
        this.kind = kind;
        this.status = ReminderStatus.AWAITING_APPROVAL;
        this.partyId = partyId;
        this.documentId = documentId;
        this.contact = contact;
        this.noteOnly = noteOnly;
        this.approvedPayload = payload;
        this.scheduleVersion = 0;
        this.scheduledAt = scheduledAt;
        this.localDate = localDate;
        this.localTime = localTime;
        this.timezone = timezone;
        this.consent = consent;
        this.templateName = templateName;
    }

    public void schedule(Long amountPaise) {
        this.status = ReminderStatus.SCHEDULED;
        this.approvedAmountPaise = amountPaise;
        this.scheduleVersion = this.scheduleVersion + 1;
    }

    public void cancel() {
        this.status = ReminderStatus.CANCELED;
        this.outcome = "canceled";
    }

    public void suppressPaid() {
        this.status = ReminderStatus.SUPPRESSED_PAID;
        this.outcome = "fully paid";
    }

    public void needsReapproval() {
        this.status = ReminderStatus.NEEDS_REAPPROVAL;
        this.outcome = "balance changed";
    }

    public void miss() {
        this.status = ReminderStatus.MISSED;
        this.outcome = "past the grace window";
    }

    public void block(String reason) {
        this.status = ReminderStatus.BLOCKED;
        this.outcome = reason;
    }

    public void dashboard() {
        this.status = ReminderStatus.DASHBOARD;
        this.outcome = "dashboard";
    }

    public void reserve() {
        this.status = ReminderStatus.SENDING;
        this.sendKey = id + ":" + scheduleVersion;
    }

    public void unknown() {
        this.status = ReminderStatus.OUTCOME_UNKNOWN;
        this.outcome = "provider outcome unknown";
    }

    public void recordProvider(String providerStatus, String providerId) {
        this.providerStatus = providerStatus;
        this.providerId = providerId;
        if ("accepted".equals(providerStatus)) {
            this.status = ReminderStatus.PROVIDER_ACCEPTED;
        } else if ("delivered".equals(providerStatus)) {
            this.status = ReminderStatus.DELIVERED;
        } else if ("failed".equals(providerStatus)) {
            this.status = ReminderStatus.FAILED;
        } else if ("blocked".equals(providerStatus)) {
            this.status = ReminderStatus.BLOCKED;
        } else {
            this.status = ReminderStatus.OUTCOME_UNKNOWN;
        }
        this.outcome = providerStatus;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public ReminderKind getKind() {
        return kind;
    }

    public ReminderStatus getStatus() {
        return status;
    }

    public UUID getActorId() {
        return actorId;
    }

    public UUID getPartyId() {
        return partyId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public boolean isNoteOnly() {
        return noteOnly;
    }

    public Long getApprovedAmountPaise() {
        return approvedAmountPaise;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public LocalDate getLocalDate() {
        return localDate;
    }

    public LocalTime getLocalTime() {
        return localTime;
    }

    public String getProviderStatus() {
        return providerStatus;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getContact() {
        return contact;
    }

    public String getTemplateName() {
        return templateName;
    }

    public String getApprovedPayload() {
        return approvedPayload;
    }
}
