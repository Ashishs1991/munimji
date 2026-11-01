package in.munimsaathi.reminder;

import in.munimsaathi.config.MunimProperties;
import in.munimsaathi.jobs.Job;
import in.munimsaathi.jobs.JobClaims;
import in.munimsaathi.ledger.DocumentKind;
import in.munimsaathi.ledger.PostedDocument;
import in.munimsaathi.ledger.PostedDocumentRepository;
import in.munimsaathi.ledger.PostingService;
import in.munimsaathi.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

@Service
public class ReminderBook {

    public static final String JOB_KIND = "REMINDER";

    private final TenantContext tenants;
    private final ReminderTaskRepository tasks;
    private final PostedDocumentRepository documents;
    private final PostingService posting;
    private final JobClaims jobs;
    private final MessageSender sender;
    private final TransactionTemplate transactions;
    private final Duration grace;

    public ReminderBook(
            TenantContext tenants,
            ReminderTaskRepository tasks,
            PostedDocumentRepository documents,
            PostingService posting,
            JobClaims jobs,
            MessageSender sender,
            TransactionTemplate transactions,
            MunimProperties properties
    ) {
        this.tenants = tenants;
        this.tasks = tasks;
        this.documents = documents;
        this.posting = posting;
        this.jobs = jobs;
        this.sender = sender;
        this.transactions = transactions;
        int minutes = properties.reminders() == null ? 15 : properties.reminders().graceMinutes();
        this.grace = Duration.ofMinutes(minutes);
    }

    @Transactional
    public UUID propose(
            UUID userId,
            ReminderKind kind,
            UUID partyId,
            UUID documentId,
            String contact,
            LocalDate localDate,
            LocalTime localTime,
            String payload,
            boolean consent,
            String templateName,
            boolean noteOnly
    ) {
        if (localDate == null || localTime == null) {
            throw new IllegalArgumentException("Ask for the sending time.");
        }
        UUID businessId = tenants.currentBusinessId(userId);
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        Instant scheduledAt = LocalDateTime.of(localDate, localTime).atZone(zone).toInstant();
        if (!scheduledAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("That time has already passed.");
        }
        if (kind == ReminderKind.CUSTOMER_SEND) {
            if (noteOnly || contact == null || contact.isBlank() || !consent || templateName == null || templateName.isBlank()) {
                throw new IllegalArgumentException("A customer send needs a consenting contact and a template.");
            }
            requireLinkedSale(businessId, partyId, documentId);
        }
        if (documentId != null) {
            requireLinkedSale(businessId, partyId, documentId);
        }
        ReminderTask task = new ReminderTask(
                UUID.randomUUID(),
                businessId,
                userId,
                kind,
                partyId,
                documentId,
                blankToNull(contact),
                noteOnly,
                payload,
                scheduledAt,
                localDate,
                localTime,
                "Asia/Kolkata",
                consent,
                blankToNull(templateName)
        );
        tasks.save(task);
        return task.getId();
    }

    @Transactional
    public void approve(UUID userId, UUID taskId) {
        ReminderTask task = require(userId, taskId);
        if (task.getStatus() != ReminderStatus.AWAITING_APPROVAL && task.getStatus() != ReminderStatus.NEEDS_REAPPROVAL) {
            throw new IllegalArgumentException("This approval is no longer current.");
        }
        Long amount = null;
        if (!task.isNoteOnly()) {
            amount = posting.customerReceivablePaise(userId, task.getPartyId());
        }
        task.schedule(amount);
        jobs.enqueue(task.getBusinessId(), JOB_KIND, task.getId().toString(), task.getScheduledAt());
    }

    @Transactional
    public void cancel(UUID userId, UUID taskId) {
        ReminderTask task = require(userId, taskId);
        if (task.getStatus() == ReminderStatus.SENDING) {
            task.unknown();
            return;
        }
        if (task.getStatus() != ReminderStatus.SCHEDULED && task.getStatus() != ReminderStatus.AWAITING_APPROVAL) {
            throw new IllegalArgumentException("This task can no longer be canceled.");
        }
        task.cancel();
    }

    public void dispatch(Job job, String workerId, Instant now) {
        Prepared prepared = transactions.execute(status -> prepare(UUID.fromString(job.getPayload()), now));
        if (prepared == null) {
            jobs.complete(job.getId(), workerId, "missing", now);
            return;
        }
        if (prepared.call()) {
            ProviderReceipt receipt;
            try {
                receipt = sender.send(prepared.contact(), prepared.templateName(), prepared.payload());
            } catch (RuntimeException exception) {
                receipt = new ProviderReceipt("outcome_unknown", null);
            }
            ProviderReceipt recorded = receipt;
            transactions.executeWithoutResult(status -> record(prepared.taskId(), recorded));
            jobs.complete(job.getId(), workerId, recorded.status(), now);
            return;
        }
        jobs.complete(job.getId(), workerId, prepared.outcome(), now);
    }

    private Prepared prepare(UUID taskId, Instant now) {
        ReminderTask task = tasks.findById(taskId).orElseThrow();
        if (task.getStatus() == ReminderStatus.CANCELED) {
            return Prepared.skip(task.getId(), "canceled");
        }
        if (task.getStatus() == ReminderStatus.SENDING || task.getStatus() == ReminderStatus.OUTCOME_UNKNOWN) {
            task.unknown();
            return Prepared.skip(task.getId(), "provider outcome unknown");
        }
        if (task.getStatus() != ReminderStatus.SCHEDULED) {
            return Prepared.skip(task.getId(), task.getOutcome() == null ? "closed" : task.getOutcome());
        }
        if (now.isAfter(task.getScheduledAt().plus(grace))) {
            task.miss();
            return Prepared.skip(task.getId(), "past the grace window");
        }
        if (!task.isNoteOnly()) {
            long balance = posting.customerReceivablePaiseForBusiness(task.getBusinessId(), task.getPartyId());
            if (balance == 0) {
                task.suppressPaid();
                return Prepared.skip(task.getId(), "fully paid");
            }
            if (task.getApprovedAmountPaise() == null || balance != task.getApprovedAmountPaise()) {
                task.needsReapproval();
                return Prepared.skip(task.getId(), "balance changed");
            }
        }
        if (task.getKind() == ReminderKind.OWNER_REMINDER) {
            task.dashboard();
            return Prepared.skip(task.getId(), "dashboard");
        }
        task.reserve();
        return new Prepared(task.getId(), true, task.getContact(), task.getTemplateName(), task.getApprovedPayload(), "sending");
    }

    private void record(UUID taskId, ProviderReceipt receipt) {
        ReminderTask task = tasks.findById(taskId).orElseThrow();
        if (receipt == null || receipt.status() == null || "outcome_unknown".equals(receipt.status())) {
            task.unknown();
            return;
        }
        task.recordProvider(receipt.status(), receipt.providerId());
    }

    private void requireLinkedSale(UUID businessId, UUID partyId, UUID documentId) {
        PostedDocument document = documents.findById(documentId == null ? UUID.randomUUID() : documentId).orElse(null);
        if (document == null
                || !document.getBusinessId().equals(businessId)
                || document.getKind() != DocumentKind.SALE
                || partyId == null
                || !partyId.equals(document.getPartyId())) {
            throw new IllegalArgumentException("Link an existing receivable before sending.");
        }
    }

    private ReminderTask require(UUID userId, UUID taskId) {
        UUID businessId = tenants.currentBusinessId(userId);
        ReminderTask task = tasks.findById(taskId).orElseThrow();
        if (!task.getBusinessId().equals(businessId)) {
            throw new IllegalArgumentException("This task is not in the selected business.");
        }
        return task;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record Prepared(
            UUID taskId,
            boolean call,
            String contact,
            String templateName,
            String payload,
            String outcome
    ) {
        static Prepared skip(UUID taskId, String outcome) {
            return new Prepared(taskId, false, null, null, null, outcome);
        }
    }
}
