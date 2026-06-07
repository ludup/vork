package sh.vork.scheduling.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import com.jadaptive.orm.DatabaseRepository;
import sh.vork.scheduling.domain.InvocationType;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;

/**
 * Runnable wrapper for executing one scheduled AI job invocation.
 *
 * <p>At the start of each execution the job is reloaded from the database so
 * that its current status can be checked.  If a previous run is still ACTIVE
 * or AWAITING_INPUT the execution is skipped — this prevents overlapping runs
 * for REPEAT jobs.  The job is immediately marked ACTIVE before the AI call so
 * that any concurrent fire also skips cleanly.
 */
public class AiJobRunner implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AiJobRunner.class);

    private final ScheduledJob job;
    private final BackgroundOrchestrationEngine backgroundOrchestrationEngine;
    private final DatabaseRepository<ScheduledJob> jobRepository;
    private final DatabaseRepository<AiSession> sessionRepository;

    public AiJobRunner(ScheduledJob job,
                       BackgroundOrchestrationEngine backgroundOrchestrationEngine,
                       DatabaseRepository<ScheduledJob> jobRepository,
                       DatabaseRepository<AiSession> sessionRepository) {
        this.job = job;
        this.backgroundOrchestrationEngine = backgroundOrchestrationEngine;
        this.jobRepository = jobRepository;
        this.sessionRepository = sessionRepository;
    }

    @Override
    public void run() {
        // ── Guard: reload current state and skip if already in-flight ──────────
        ScheduledJob current = jobRepository.get(job.id());
        if (current == null) {
            log.warn("Job not found at execution time, skipping [id={}]", job.id());
            return;
        }
        if (current.status() == ScheduledJobStatus.ACTIVE
                || current.status() == ScheduledJobStatus.AWAITING_INPUT) {
            log.warn("Skipping execution: previous run still in progress [id={}, status={}]",
                    job.id(), current.status());
            return;
        }
        if (current.status() == ScheduledJobStatus.PAUSED) {
            log.info("Skipping execution: job is paused [id={}]", job.id());
            return;
        }

        // ── Mark ACTIVE before spawning so concurrent fires are blocked ────────
        save(ScheduledJobStatus.ACTIVE, current.lastExecutionTime(), current.nextExecutionTime());

        String trackingSessionUuid = job.id() + "-run-" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        try {
            String effectiveProvider = (job.provider() != null && !job.provider().isBlank())
                    ? job.provider()
                    : AiProvider.BACKGROUND_SCHEDULER.name();

            AiChatMessage seedMessage = new AiChatMessage(
                    UUID.randomUUID().toString(),
                    "USER",
                    job.aiPrompt(),
                    now,
                    null);

            AiSession trackingSession = new AiSession(
                    trackingSessionUuid,
                    effectiveProvider,
                    SessionOriginMode.BACKGROUND,
                    job.userId(),
                    "Job: " + job.name(),
                    now,
                    0,
                    List.of(seedMessage),
                    buildEnvVars(job),
                    AiSessionStatus.RUNNING,
                    job.agentTemplateId(),
                    job.modelId());
            sessionRepository.save(trackingSession);

            SecurityContextHolder.getContext()
                    .setAuthentication(new SystemBackgroundAuthentication(job.userId()));

            log.info("Executing scheduled AI job [id={}, name={}, user={}, type={}, tracking={}]",
                    job.id(), job.name(), job.userId(), job.invocationType(), trackingSessionUuid);

            backgroundOrchestrationEngine.executeBackgroundTurn(trackingSessionUuid, job.aiPrompt());

            AiSession finalSession = sessionRepository.get(trackingSessionUuid);
            log.info("Scheduled AI job run finished [id={}, type={}, tracking={}, sessionStatus={}]",
                    job.id(), job.invocationType(), trackingSessionUuid,
                    finalSession == null ? "<missing>" : finalSession.status());

            updateJobAfterRun(now, finalSession, trackingSessionUuid);

        } catch (Exception ex) {
            log.error("Scheduled AI job failed [id={}]: {}", job.id(), ex.getMessage(), ex);
            // Reset to WAITING so the job can retry on next scheduled interval
            save(ScheduledJobStatus.WAITING, current.lastExecutionTime(), current.nextExecutionTime());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void updateJobAfterRun(long executionTime, AiSession finalSession, String trackingSessionUuid) {
        InvocationType type = job.invocationType() != null ? job.invocationType() : InvocationType.ONE_TIME;

        // Compute the next scheduled execution time for repeating jobs
        long nextExec;
        if (type == InvocationType.REPEAT && job.repeatDuration() > 0) {
            nextExec = executionTime
                    + AiSchedulerService.toDuration(job.repeatDuration(), job.durationType()).toMillis();
        } else {
            nextExec = 0L;
        }

        boolean awaitingInput = finalSession != null
                && finalSession.status() == AiSessionStatus.AWAITING_INPUT;

        switch (type) {
            case ONE_TIME -> {
                if (awaitingInput) {
                    save(ScheduledJobStatus.AWAITING_INPUT, executionTime, 0L);
                    log.info("ONE_TIME job awaiting authorization [id={}, tracking={}]",
                            job.id(), trackingSessionUuid);
                } else {
                    save(ScheduledJobStatus.WAITING, executionTime, 0L);
                    log.info("ONE_TIME job finished [id={}]", job.id());
                }
            }
            case REPEAT -> {
                if (awaitingInput) {
                    save(ScheduledJobStatus.AWAITING_INPUT, executionTime, nextExec);
                    log.info("REPEAT job awaiting authorization [id={}, tracking={}]",
                            job.id(), trackingSessionUuid);
                } else {
                    save(ScheduledJobStatus.WAITING, executionTime, nextExec);
                    log.info("REPEAT job execution recorded [id={}, nextRun={}]", job.id(), nextExec);
                }
            }
            case MANUAL -> {
                save(ScheduledJobStatus.WAITING, executionTime, 0L);
                log.info("MANUAL job execution recorded [id={}]", job.id());
            }
        }
    }

    private void save(ScheduledJobStatus newStatus, long lastExecutionTime, long nextExecutionTime) {
        jobRepository.save(new ScheduledJob(
                job.id(),
                job.name(),
                job.aiPrompt(),
                job.sessionUuid(),
                job.userId(),
                job.invocationType(),
                job.startTime(),
                job.repeatDuration(),
                job.durationType(),
                lastExecutionTime,
                nextExecutionTime,
                job.agentTemplateId(),
                job.provider(),
                job.modelId(),
                job.oobTimeoutMinutes(),
                newStatus));
    }

    private static java.util.concurrent.ConcurrentHashMap<String, String> buildEnvVars(ScheduledJob job) {
        java.util.concurrent.ConcurrentHashMap<String, String> env = AiSession.defaultEnvironmentVariables();
        if (job.oobTimeoutMinutes() > 0) {
            env.put("vork.oob.timeout.minutes", String.valueOf(job.oobTimeoutMinutes()));
        }
        return env;
    }
}
