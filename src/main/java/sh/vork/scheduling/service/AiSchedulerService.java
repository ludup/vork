package sh.vork.scheduling.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import sh.vork.ai.service.AiOrchestrationService;
import sh.vork.database.DatabaseRepository;
import sh.vork.scheduling.domain.DurationType;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;

/**
 * Core programmatic scheduler for persistent AI jobs.
 */
@Service
public class AiSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(AiSchedulerService.class);

    private final ThreadPoolTaskScheduler taskScheduler;
    private final DatabaseRepository<ScheduledJob> jobRepository;
    private final AiOrchestrationService aiService;

    private final Map<String, ScheduledFuture<?>> activeFutures = new ConcurrentHashMap<>();

    public AiSchedulerService(ThreadPoolTaskScheduler taskScheduler,
                              DatabaseRepository<ScheduledJob> jobRepository,
                              AiOrchestrationService aiService) {
        this.taskScheduler = taskScheduler;
        this.jobRepository = jobRepository;
        this.aiService = aiService;
    }

    public ScheduledJob scheduleJob(ScheduledJob job) {
        String id = ensureId(job.id());

        ScheduledJob normalized = new ScheduledJob(
                id,
                job.aiPrompt(),
                job.sessionUuid(),
                job.username(),
                job.startTime() == null ? Instant.now() : job.startTime(),
                job.repeatDuration(),
                job.durationType() == null ? DurationType.SECONDS : job.durationType(),
                job.status() == null ? ScheduledJobStatus.ACTIVE : job.status());

        ScheduledFuture<?> existing = activeFutures.remove(id);
        if (existing != null) {
            existing.cancel(true);
        }

        jobRepository.save(normalized);

        AiJobRunner runner = new AiJobRunner(normalized, aiService, jobRepository);
        Instant start = normalized.startTime();

        ScheduledFuture<?> future;
        if (normalized.repeatDuration() <= 0) {
            future = taskScheduler.schedule(runner, start);
        } else {
            Duration interval = toDuration(normalized.repeatDuration(), normalized.durationType());
            future = taskScheduler.scheduleAtFixedRate(runner, start, interval);
        }

        activeFutures.put(id, future);
        log.info("Scheduled job active [id={}, start={}, repeat={}, unit={}]",
                id, start, normalized.repeatDuration(), normalized.durationType());
        return normalized;
    }

    public void cancelJob(String jobId) {
        ScheduledFuture<?> future = activeFutures.remove(jobId);
        if (future != null) {
            future.cancel(true);
        }

        ScheduledJob existing = jobRepository.get(jobId);
        if (existing == null) {
            return;
        }

        jobRepository.save(new ScheduledJob(
                existing.id(),
                existing.aiPrompt(),
                existing.sessionUuid(),
                existing.username(),
                existing.startTime(),
                existing.repeatDuration(),
                existing.durationType(),
                ScheduledJobStatus.PAUSED));

        log.info("Scheduled job paused [id={}]", jobId);
    }

    private static Duration toDuration(long amount, DurationType type) {
        return switch (type) {
            case SECONDS -> Duration.ofSeconds(amount);
            case MINUTES -> Duration.ofMinutes(amount);
            case HOURS -> Duration.ofHours(amount);
            case DAYS -> Duration.ofDays(amount);
            case WEEKS -> Duration.ofDays(amount * 7);
            case MONTHS -> Duration.ofDays(amount * 30);
        };
    }

    private static String ensureId(String id) {
        if (id == null || id.isBlank()) {
            return java.util.UUID.randomUUID().toString();
        }
        return id;
    }
}
