package in.munimsaathi.jobs;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class JobPoller {

    private final JobWorker worker;

    public JobPoller(JobWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelay = 60_000)
    public void wake() {
        worker.poll(Instant.now());
    }
}
