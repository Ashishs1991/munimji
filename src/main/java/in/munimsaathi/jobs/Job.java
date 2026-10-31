package in.munimsaathi.jobs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
public class Job {

    @Id
    private UUID id;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "lease_owner")
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(nullable = false)
    private int attempts;

    @Column
    private String outcome;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Job() {
    }

    public Job(UUID id, UUID businessId, String kind, String payload, Instant dueAt) {
        this.id = id;
        this.businessId = businessId;
        this.kind = kind;
        this.payload = payload;
        this.status = JobStatus.PENDING;
        this.dueAt = dueAt;
        this.attempts = 0;
    }

    public void lease(String workerId, Instant until) {
        this.status = JobStatus.LEASED;
        this.leaseOwner = workerId;
        this.leaseUntil = until;
        this.attempts = this.attempts + 1;
    }

    public void finish(String outcome, Instant at) {
        this.status = JobStatus.DONE;
        this.outcome = outcome;
        this.completedAt = at;
        this.leaseOwner = null;
        this.leaseUntil = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public String getKind() {
        return kind;
    }

    public String getPayload() {
        return payload;
    }

    public JobStatus getStatus() {
        return status;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getOutcome() {
        return outcome;
    }
}
