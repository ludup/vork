package sh.vork.scheduling.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.jadaptive.orm.DatabaseRepository;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;
import sh.vork.scheduling.service.AiSchedulerService;

/**
 * Rehydrates ACTIVE scheduled jobs after application startup.
 */
@Component
public class SchedulerLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLifecycleManager.class);

    private final DatabaseRepository<ScheduledJob> jobRepository;
    private final AiSchedulerService aiSchedulerService;

    public SchedulerLifecycleManager(DatabaseRepository<ScheduledJob> jobRepository,
                                     AiSchedulerService aiSchedulerService) {
        this.jobRepository = jobRepository;
        this.aiSchedulerService = aiSchedulerService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        int resumed = 0;
        try (var stream = jobRepository.list(0, Integer.MAX_VALUE)) {
            for (ScheduledJob job : stream.toList()) {
                if (job.status() == ScheduledJobStatus.ACTIVE) {
                    aiSchedulerService.scheduleJob(job);
                    resumed++;
                }
            }
        }
        log.info("Scheduler lifecycle startup complete [resumedActiveJobs={}]", resumed);
    }
}
