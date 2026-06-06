package sh.vork.scheduling.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;

import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import com.jadaptive.orm.DatabaseRepository;
import sh.vork.scheduling.domain.DurationType;
import sh.vork.scheduling.domain.InvocationType;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;

class AiJobRunnerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static ScheduledJob makeJob(String id, InvocationType type, long repeatDuration,
                                        DurationType durationType, ScheduledJobStatus status) {
        return new ScheduledJob(id, "Job " + id, "prompt-" + id, "sid-" + id, "alice",
                type, Instant.parse("2026-05-17T10:15:30Z"),
                repeatDuration, durationType,
                0L, 0L, null, null, null, status);
    }

    @Test
    void run_oneShot_marksActiveThenWaitingOnSuccess() {
        BackgroundOrchestrationEngine engine = mock(BackgroundOrchestrationEngine.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);

        ScheduledJob job = makeJob("job-1", InvocationType.ONE_TIME, 0, DurationType.MINUTES,
                ScheduledJobStatus.WAITING);
        when(repo.get("job-1")).thenReturn(job);
        when(sessionRepo.get(anyString())).thenReturn(new AiSession(
                "sid", "BACKGROUND_SCHEDULER", SessionOriginMode.BACKGROUND, "alice", "Untitled",
                System.currentTimeMillis(), 0, List.of(), AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.COMPLETED, null, null));

        new AiJobRunner(job, engine, repo, sessionRepo).run();

        verify(engine).executeBackgroundTurn(anyString(), eq("prompt-job-1"));

        ArgumentCaptor<ScheduledJob> saved = ArgumentCaptor.forClass(ScheduledJob.class);
        verify(repo, times(2)).save(saved.capture());
        assertEquals(ScheduledJobStatus.ACTIVE, saved.getAllValues().get(0).status());
        ScheduledJob finalSave = saved.getAllValues().get(1);
        assertEquals(ScheduledJobStatus.WAITING, finalSave.status());
        assertEquals(0L, finalSave.nextExecutionTime()); // ONE_TIME has no next run
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void run_repeating_marksActiveAndRecordsNextExecution() {
        BackgroundOrchestrationEngine engine = mock(BackgroundOrchestrationEngine.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);

        ScheduledJob job = makeJob("job-2", InvocationType.REPEAT, 5, DurationType.MINUTES,
                ScheduledJobStatus.WAITING);
        when(repo.get("job-2")).thenReturn(job);
        // sessionRepo returns null — treated as non-AWAITING_INPUT, goes to WAITING

        new AiJobRunner(job, engine, repo, sessionRepo).run();

        verify(engine).executeBackgroundTurn(anyString(), eq("prompt-job-2"));

        ArgumentCaptor<ScheduledJob> saved = ArgumentCaptor.forClass(ScheduledJob.class);
        verify(repo, times(2)).save(saved.capture());
        assertEquals(ScheduledJobStatus.ACTIVE, saved.getAllValues().get(0).status());
        ScheduledJob finalSave = saved.getAllValues().get(1);
        assertEquals(ScheduledJobStatus.WAITING, finalSave.status());
        assertTrue(finalSave.nextExecutionTime() > 0, "nextExecutionTime should be set for REPEAT job");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void run_repeat_skipsWhenPreviousRunStillActive() {
        BackgroundOrchestrationEngine engine = mock(BackgroundOrchestrationEngine.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);

        ScheduledJob job = makeJob("job-3", InvocationType.REPEAT, 5, DurationType.MINUTES,
                ScheduledJobStatus.ACTIVE); // already ACTIVE
        when(repo.get("job-3")).thenReturn(job);

        new AiJobRunner(job, engine, repo, sessionRepo).run();

        verify(engine, never()).executeBackgroundTurn(any(), any());
        verify(repo, never()).save(any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void run_repeat_skipsWhenAwaitingInput() {
        BackgroundOrchestrationEngine engine = mock(BackgroundOrchestrationEngine.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);

        ScheduledJob job = makeJob("job-4", InvocationType.REPEAT, 5, DurationType.MINUTES,
                ScheduledJobStatus.AWAITING_INPUT);
        when(repo.get("job-4")).thenReturn(job);

        new AiJobRunner(job, engine, repo, sessionRepo).run();

        verify(engine, never()).executeBackgroundTurn(any(), any());
        verify(repo, never()).save(any());
    }

    @Test
    void run_aiFailure_resetsToWaitingAndClearsSecurityContext() {
        BackgroundOrchestrationEngine engine = mock(BackgroundOrchestrationEngine.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);

        ScheduledJob job = makeJob("job-5", InvocationType.ONE_TIME, 0, DurationType.MINUTES,
                ScheduledJobStatus.WAITING);
        when(repo.get("job-5")).thenReturn(job);
        doThrow(new RuntimeException("boom"))
                .when(engine).executeBackgroundTurn(anyString(), anyString());

        new AiJobRunner(job, engine, repo, sessionRepo).run();

        ArgumentCaptor<ScheduledJob> saved = ArgumentCaptor.forClass(ScheduledJob.class);
        verify(repo, times(2)).save(saved.capture()); // ACTIVE + reset to WAITING
        assertEquals(ScheduledJobStatus.ACTIVE, saved.getAllValues().get(0).status());
        assertEquals(ScheduledJobStatus.WAITING, saved.getAllValues().get(1).status());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
