package in.munimsaathi.jobs;

import in.munimsaathi.reminder.ReminderBook;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobWorker {

    static final Duration LEASE = Duration.ofMinutes(5);
    static final String PING = "PING";

    private final JobClaims claims;
    private final ReminderBook reminders;

    public JobWorker(JobClaims claims, ReminderBook reminders) {
        this.claims = claims;
        this.reminders = reminders;
    }

    public UUID enqueue(UUID businessId, String kind, String payload, Instant dueAt) {
        return claims.enqueue(businessId, kind, payload, dueAt);
    }

    public Optional<Job> claim(String workerId, Instant now) {
        return claims.claim(workerId, now, LEASE);
    }

    public void poll(Instant now) {
        String workerId = "poller-" + UUID.randomUUID();
        Optional<Job> claimed = claims.claim(workerId, now, LEASE);
        if (claimed.isEmpty()) {
            return;
        }
        Job job = claimed.get();
        if (PING.equals(job.getKind())) {
            claims.complete(job.getId(), workerId, "recorded", now);
            return;
        }
        if (ReminderBook.JOB_KIND.equals(job.getKind())) {
            reminders.dispatch(job, workerId, now);
            return;
        }
        claims.complete(job.getId(), workerId, "ignored", now);
    }
}
