package in.munimsaathi.jobs;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobClaims {

    private final EntityManager entityManager;
    private final JobRepository jobs;

    public JobClaims(EntityManager entityManager, JobRepository jobs) {
        this.entityManager = entityManager;
        this.jobs = jobs;
    }

    @Transactional
    public UUID enqueue(UUID businessId, String kind, String payload, Instant dueAt) {
        Job job = new Job(UUID.randomUUID(), businessId, kind, payload, dueAt);
        jobs.save(job);
        return job.getId();
    }

    @Transactional
    public Optional<Job> claim(String workerId, Instant now, Duration lease) {
        @SuppressWarnings("unchecked")
        List<UUID> ids = entityManager.createNativeQuery("""
                select id from jobs
                where due_at <= :now
                  and (
                    status = 'PENDING'
                    or (status = 'LEASED' and lease_until <= :now)
                  )
                order by due_at, id
                for update skip locked
                limit 1
                """)
                .setParameter("now", Timestamp.from(now))
                .getResultList();
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        Job job = jobs.findById(ids.get(0)).orElseThrow();
        job.lease(workerId, now.plus(lease));
        return Optional.of(job);
    }

    @Transactional
    public void complete(UUID jobId, String workerId, String outcome, Instant now) {
        Job job = jobs.findById(jobId).orElseThrow();
        if (job.getStatus() != JobStatus.LEASED || !workerId.equals(job.getLeaseOwner())) {
            throw new IllegalStateException("This lease is no longer current.");
        }
        job.finish(outcome, now);
    }
}
