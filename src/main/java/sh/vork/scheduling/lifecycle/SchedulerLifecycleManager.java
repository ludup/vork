package sh.vork.scheduling.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

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
        int reset = 0;
        try (var stream = jobRepository.list(0, Integer.MAX_VALUE)) {
            for (ScheduledJob job : stream.toList()) {
                if (job.status() == ScheduledJobStatus.ACTIVE
                        || job.status() == ScheduledJobStatus.AWAITING_INPUT) {
                    // Job was in-flight when the app stopped — reset to WAITING so it
                    // can be re-scheduled safely without skipping.
                    jobRepository.save(new ScheduledJob(
                            job.id(), job.name(), job.aiPrompt(), job.sessionUuid(),
                            job.userId(), job.invocationType(), job.startTime(),
                            job.repeatDuration(), job.durationType(), job.lastExecutionTime(),
                            job.nextExecutionTime(), job.agentTemplateId(), job.provider(),
                            job.modelId(), job.oobTimeoutMinutes(), ScheduledJobStatus.WAITING));
                    log.warn("Resetting stuck job to WAITING on startup [id={}, was={}]",
                            job.id(), job.status());
                    reset++;
                }
                if (job.status() == ScheduledJobStatus.WAITING
                        || job.status() == ScheduledJobStatus.ACTIVE
                        || job.status() == ScheduledJobStatus.AWAITING_INPUT) {
                    // Re-schedule: compute next fire time to avoid re-running missed intervals
                    Instant effectiveStart = aiSchedulerService.computeEffectiveStart(job);
                    ScheduledJob adjusted = new ScheduledJob(
                            job.id(), job.name(), job.aiPrompt(), job.sessionUuid(),
                            job.userId(), job.invocationType(), effectiveStart,
                            job.repeatDuration(), job.durationType(), job.lastExecutionTime(),
                            job.nextExecutionTime(), job.agentTemplateId(), job.provider(),
                            job.modelId(), job.oobTimeoutMinutes(), ScheduledJobStatus.WAITING);
                    aiSchedulerService.scheduleJob(adjusted);
                    resumed++;
                }
            }
        }
        log.info("Scheduler lifecycle startup complete [scheduledJobs={}, resetStuck={}]",
                resumed, reset);
    }
}
