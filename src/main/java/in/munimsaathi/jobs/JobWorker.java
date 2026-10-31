package in.munimsaathi.jobs;

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

    public JobWorker(JobClaims claims) {
        this.claims = claims;
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
        String outcome = PING.equals(job.getKind()) ? "recorded" : "ignored";
        claims.complete(job.getId(), workerId, outcome, now);
    }
}
