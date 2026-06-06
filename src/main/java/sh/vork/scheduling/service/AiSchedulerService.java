package sh.vork.scheduling.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import com.jadaptive.orm.DatabaseRepository;
import sh.vork.scheduling.domain.DurationType;
import sh.vork.scheduling.domain.InvocationType;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;

/**
 * Core programmatic scheduler for persistent AI jobs.
 *
 * <p>Supports MANUAL, ONE_TIME, and REPEAT invocation types.  REPEAT jobs
 * calculate their effective start time on startup from {@code lastExecutionTime}
 * so missed intervals are skipped and the next interval fires correctly.
 */
@Service
public class AiSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(AiSchedulerService.class);

    private final ThreadPoolTaskScheduler taskScheduler;
    private final DatabaseRepository<ScheduledJob> jobRepository;
    private final BackgroundOrchestrationEngine backgroundOrchestrationEngine;
    private final DatabaseRepository<AiSession> sessionRepository;

    private final Map<String, ScheduledFuture<?>> activeFutures = new ConcurrentHashMap<>();

    public AiSchedulerService(@Qualifier("aiTaskScheduler") ThreadPoolTaskScheduler taskScheduler,
                              DatabaseRepository<ScheduledJob> jobRepository,
                              BackgroundOrchestrationEngine backgroundOrchestrationEngine,
                              DatabaseRepository<AiSession> sessionRepository) {
        this.taskScheduler = taskScheduler;
        this.jobRepository = jobRepository;
        this.backgroundOrchestrationEngine = backgroundOrchestrationEngine;
        this.sessionRepository = sessionRepository;
    }

    // ── Schedule / reschedule ─────────────────────────────────────────────────

    /**
     * Persists and activates a job according to its {@link InvocationType}.
     * MANUAL jobs are saved but not scheduled.
     */
    public ScheduledJob scheduleJob(ScheduledJob job) {
        String id = ensureId(job.id());
        InvocationType type = job.invocationType() != null ? job.invocationType() : InvocationType.ONE_TIME;

        // Build a base normalized record first (nextExecutionTime computed after effectiveStart)
        ScheduledJob base = new ScheduledJob(
                id,
                job.name(),
                job.aiPrompt(),
                job.sessionUuid(),
                job.userId(),
                type,
                job.startTime() == null ? Instant.now() : job.startTime(),
                job.repeatDuration(),
                job.durationType() == null ? DurationType.MINUTES : job.durationType(),
                job.lastExecutionTime(),
                job.nextExecutionTime(),
                job.agentTemplateId(),
                job.provider(),
                job.modelId(),
                job.status() == null ? ScheduledJobStatus.WAITING : job.status());

        // Cancel any existing future for this id
        cancelFuture(id);

        if (type == InvocationType.MANUAL) {
            jobRepository.save(base);
            log.info("MANUAL job saved (no schedule) [id={}]", id);
            return base;
        }

        Instant effectiveStart = computeEffectiveStart(base);
        long nextExec = effectiveStart.toEpochMilli();
        ScheduledJob normalized = new ScheduledJob(
                id, base.name(), base.aiPrompt(), base.sessionUuid(),
                base.userId(), type, base.startTime(), base.repeatDuration(),
                base.durationType(), base.lastExecutionTime(), nextExec,
                base.agentTemplateId(), base.provider(), base.modelId(), base.status());
        jobRepository.save(normalized);

        AiJobRunner runner = new AiJobRunner(normalized, backgroundOrchestrationEngine,
                jobRepository, sessionRepository);

        ScheduledFuture<?> future;
        if (type == InvocationType.ONE_TIME) {
            future = taskScheduler.schedule(runner, effectiveStart);
            log.info("ONE_TIME job scheduled [id={}, fireAt={}]", id, effectiveStart);
        } else { // REPEAT
            Duration interval = toDuration(normalized.repeatDuration(), normalized.durationType());
            future = taskScheduler.scheduleAtFixedRate(runner, effectiveStart, interval);
            log.info("REPEAT job scheduled [id={}, firstFire={}, interval={} {}]",
                    id, effectiveStart, normalized.repeatDuration(), normalized.durationType());
        }
        activeFutures.put(id, future);
        return normalized;
    }

    /**
     * Computes the effective start instant for a job that is being (re-)scheduled.
     * <ul>
     *   <li>For ONE_TIME: use stored {@code startTime} as-is.
     *   <li>For REPEAT: if never run, use {@code startTime}; otherwise advance by
     *       full intervals from {@code lastExecutionTime} until the result is in the future.
     * </ul>
     */
    public Instant computeEffectiveStart(ScheduledJob job) {
        Instant base = job.startTime() == null ? Instant.now() : job.startTime();

        if (job.invocationType() != InvocationType.REPEAT
                || job.lastExecutionTime() == 0
                || job.repeatDuration() <= 0) {
            // No history or not a repeat — use startTime, floor to now if past
            return base.isBefore(Instant.now()) ? Instant.now() : base;
        }

        Duration interval = toDuration(job.repeatDuration(), job.durationType());
        Instant next = Instant.ofEpochMilli(job.lastExecutionTime()).plus(interval);
        // Keep advancing until next is in the future
        Instant now = Instant.now();
        while (next.isBefore(now)) {
            next = next.plus(interval);
        }
        return next;
    }

    // ── Pause / Resume ────────────────────────────────────────────────────────

    public void pauseJob(String jobId) {
        cancelFuture(jobId);
        ScheduledJob existing = jobRepository.get(jobId);
        if (existing == null) return;
        jobRepository.save(withStatus(existing, ScheduledJobStatus.PAUSED));
        log.info("Job paused [id={}]", jobId);
    }

    public void resumeJob(String jobId) {
        ScheduledJob existing = jobRepository.get(jobId);
        if (existing == null) return;
        scheduleJob(withStatus(existing, ScheduledJobStatus.WAITING));
        log.info("Job resumed [id={}]", jobId);
    }

    // ── Run Now ───────────────────────────────────────────────────────────────

    /**
     * Executes a job immediately in the scheduler thread pool regardless of its
     * next scheduled fire time.  For REPEAT jobs the scheduled future is unchanged;
     * for ONE_TIME the scheduled future is cancelled (avoid a double execution).
     */
    public void runNow(String jobId) {
        ScheduledJob job = jobRepository.get(jobId);
        if (job == null) {
            log.warn("runNow: job not found [id={}]", jobId);
            return;
        }
        if (job.invocationType() == InvocationType.ONE_TIME) {
            cancelFuture(jobId); // prevent the scheduled run from also firing
        }
        AiJobRunner runner = new AiJobRunner(job, backgroundOrchestrationEngine,
                jobRepository, sessionRepository);
        taskScheduler.execute(runner);
        log.info("Job triggered via Run Now [id={}, type={}]", jobId, job.invocationType());
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    public void deleteJob(String jobId) {
        cancelFuture(jobId);
        jobRepository.delete(jobId);
        log.info("Job deleted [id={}]", jobId);
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    /** Returns all jobs belonging to the given user. */
    public List<ScheduledJob> listJobsForUser(String userId) {
        try (var stream = jobRepository.list(0, Integer.MAX_VALUE)) {
            return stream.filter(j -> userId.equals(j.userId())).collect(Collectors.toList());
        }
    }

    // ── Authorization-resume handshake ────────────────────────────────────────

    public void resumeBackgroundSession(String sessionUuid) {
        log.info("Resuming background session [trackingSession={}]", sessionUuid);
        backgroundOrchestrationEngine.executeBackgroundTurn(sessionUuid, null);
        reconcileOneShotJobCompletion(sessionUuid);
    }

    private void reconcileOneShotJobCompletion(String trackingSessionUuid) {
        String marker = "-run-";
        int idx = trackingSessionUuid == null ? -1 : trackingSessionUuid.indexOf(marker);
        if (idx <= 0) return;

        String jobId = trackingSessionUuid.substring(0, idx);
        ScheduledJob job = jobRepository.get(jobId);
        if (job == null) return;

        // Only reconcile non-repeating jobs
        if (job.invocationType() == InvocationType.REPEAT) return;

        AiSession finalSession = sessionRepository.get(trackingSessionUuid);
        if (finalSession != null && finalSession.status() == AiSessionStatus.COMPLETED) {
            // ONE_TIME job has no further runs — clear nextExecutionTime and set WAITING
            jobRepository.save(new ScheduledJob(
                    job.id(), job.name(), job.aiPrompt(), job.sessionUuid(),
                    job.userId(), job.invocationType(), job.startTime(), job.repeatDuration(),
                    job.durationType(), job.lastExecutionTime(), 0L,
                    job.agentTemplateId(), job.provider(), job.modelId(), ScheduledJobStatus.WAITING));
            log.info("Job marked waiting after authorization resume [id={}, tracking={}]",
                    job.id(), trackingSessionUuid);
        } else {
            log.info("Authorization resume finished, job not yet complete [id={}, tracking={}, status={}]",
                    job.id(), trackingSessionUuid,
                    finalSession == null ? "<missing>" : finalSession.status());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void cancelFuture(String jobId) {
        ScheduledFuture<?> f = activeFutures.remove(jobId);
        if (f != null) f.cancel(false);
    }

    private static ScheduledJob withStatus(ScheduledJob job, ScheduledJobStatus status) {
        return new ScheduledJob(job.id(), job.name(), job.aiPrompt(), job.sessionUuid(),
                job.userId(), job.invocationType(), job.startTime(), job.repeatDuration(),
                job.durationType(), job.lastExecutionTime(), job.nextExecutionTime(),
                job.agentTemplateId(), job.provider(), job.modelId(), status);
    }

    public static Duration toDuration(long amount, DurationType type) {
        return switch (type) {
            case SECONDS -> Duration.ofSeconds(amount);
            case MINUTES -> Duration.ofMinutes(amount);
            case HOURS   -> Duration.ofHours(amount);
            case DAYS    -> Duration.ofDays(amount);
            case WEEKS   -> Duration.ofDays(amount * 7);
            case MONTHS  -> Duration.ofDays(amount * 30);
        };
    }

    private static String ensureId(String id) {
        return (id == null || id.isBlank()) ? java.util.UUID.randomUUID().toString() : id;
    }
}
