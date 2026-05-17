package sh.vork.scheduling.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;

import sh.vork.ai.AiProvider;
import sh.vork.ai.service.AiOrchestrationService;
import sh.vork.database.DatabaseRepository;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;

/**
 * Runnable wrapper for executing one scheduled AI job invocation.
 */
public class AiJobRunner implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AiJobRunner.class);

    private final ScheduledJob job;
    private final AiOrchestrationService aiService;
    private final DatabaseRepository<ScheduledJob> jobRepository;

    public AiJobRunner(ScheduledJob job,
                       AiOrchestrationService aiService,
                       DatabaseRepository<ScheduledJob> jobRepository) {
        this.job = job;
        this.aiService = aiService;
        this.jobRepository = jobRepository;
    }

    @Override
    public void run() {
        try {
            SecurityContextHolder.getContext()
                    .setAuthentication(new SystemBackgroundAuthentication(job.username()));

            log.info("Executing scheduled AI job [id={}, user={}, session={}]",
                    job.id(), job.username(), job.sessionUuid());

            aiService.generate(job.aiPrompt(), AiProvider.GEMINI);

            if (job.repeatDuration() == 0) {
                jobRepository.save(new ScheduledJob(
                        job.id(),
                    job.name(),
                        job.aiPrompt(),
                        job.sessionUuid(),
                        job.username(),
                        job.startTime(),
                        job.repeatDuration(),
                        job.durationType(),
                        ScheduledJobStatus.COMPLETED));
                log.info("Scheduled AI job marked completed [id={}]", job.id());
            }
        } catch (Exception ex) {
            log.error("Scheduled AI job failed [id={}]: {}", job.id(), ex.getMessage(), ex);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
