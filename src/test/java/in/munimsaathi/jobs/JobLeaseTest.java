package in.munimsaathi.jobs;

import in.munimsaathi.PostgresSupport;
import in.munimsaathi.tenant.Business;
import in.munimsaathi.tenant.BusinessRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JobLeaseTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresSupport.register(registry);
    }

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private JobWorker worker;

    @Autowired
    private JobClaims claims;

    @Autowired
    private JobPoller poller;

    @Autowired
    private JobRepository jobs;

    @Test
    void aDuePingIsRecordedOnceWhenThePollerWakes() {
        UUID businessId = business();
        Instant due = Instant.now().minusSeconds(5);
        UUID jobId = worker.enqueue(businessId, "PING", "{}", due);

        poller.wake();
        poller.wake();

        Job job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(job.getOutcome()).isEqualTo("recorded");
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getBusinessId()).isEqualTo(businessId);
        assertThat(job.getPayload()).isEqualTo("{}");
    }

    @Test
    void aRestartDoesNotClaimTheSameLiveLease() throws Exception {
        UUID businessId = business();
        Instant now = Instant.parse("2099-01-01T00:00:00Z");
        UUID jobId = worker.enqueue(businessId, "PING", "{}", now);

        List<Optional<UUID>> claimed = claimTogether(now);
        assertThat(claimed).filteredOn(Optional::isPresent).hasSize(1);
        assertThat(claimed).filteredOn(Optional::isEmpty).hasSize(1);

        Optional<Job> restarted = worker.claim("worker-after-restart", now);
        assertThat(restarted).isEmpty();

        Job job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.LEASED);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getLeaseUntil()).isEqualTo(now.plus(Duration.ofMinutes(5)));
        assertThat(job.getOutcome()).isNull();
    }

    @Test
    void anExpiredLeaseCanBeClaimedOnceMore() {
        UUID businessId = business();
        Instant now = Instant.parse("2099-06-01T00:00:00Z");
        UUID jobId = worker.enqueue(businessId, "PING", "{}", now);

        Job first = worker.claim("worker-a", now).orElseThrow();
        assertThat(first.getId()).isEqualTo(jobId);

        Job second = worker.claim("worker-b", now.plus(Duration.ofMinutes(5))).orElseThrow();
        assertThat(second.getId()).isEqualTo(jobId);
        assertThat(second.getLeaseOwner()).isEqualTo("worker-b");
        assertThat(second.getAttempts()).isEqualTo(2);

        assertThat(worker.claim("worker-c", now.plus(Duration.ofMinutes(5)))).isEmpty();
    }

    private List<Optional<UUID>> claimTogether(Instant now) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Optional<UUID>> first = pool.submit(() -> claimAfter(start, "worker-a", now));
            Future<Optional<UUID>> second = pool.submit(() -> claimAfter(start, "worker-b", now));
            start.countDown();
            List<Optional<UUID>> claimed = new ArrayList<>();
            claimed.add(first.get());
            claimed.add(second.get());
            return claimed;
        } finally {
            pool.shutdownNow();
        }
    }

    private Optional<UUID> claimAfter(CountDownLatch start, String workerId, Instant now) throws InterruptedException {
        start.await();
        return worker.claim(workerId, now).map(Job::getId);
    }

    private UUID business() {
        Business business = new Business(UUID.randomUUID(), "Job Shop " + UUID.randomUUID(), "Asia/Kolkata", "INR");
        businesses.save(business);
        return business.getId();
    }
}
