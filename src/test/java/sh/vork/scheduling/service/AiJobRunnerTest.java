package sh.vork.scheduling.service;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import sh.vork.ai.AiProvider;
import sh.vork.ai.service.AiOrchestrationService;
import sh.vork.database.DatabaseRepository;
import sh.vork.scheduling.domain.DurationType;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;

class AiJobRunnerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void run_oneShot_executesAiAndMarksCompleted() {
        AiOrchestrationService aiService = mock(AiOrchestrationService.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);

        ScheduledJob job = new ScheduledJob(
                "job-1",
                "Run once",
                "sid-1",
                "alice",
                Instant.parse("2026-05-17T10:15:30Z"),
                0,
                DurationType.MINUTES,
                ScheduledJobStatus.ACTIVE);

        AiJobRunner runner = new AiJobRunner(job, aiService, repo);
        runner.run();

        verify(aiService).generate("Run once", AiProvider.GEMINI);
        verify(repo).save(new ScheduledJob(
                "job-1",
                "Run once",
                "sid-1",
                "alice",
                Instant.parse("2026-05-17T10:15:30Z"),
                0,
                DurationType.MINUTES,
                ScheduledJobStatus.COMPLETED));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void run_repeating_executesAiWithoutCompletionUpdate() {
        AiOrchestrationService aiService = mock(AiOrchestrationService.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);

        ScheduledJob job = new ScheduledJob(
                "job-2",
                "Repeat",
                "sid-2",
                "bob",
                Instant.parse("2026-05-17T10:15:30Z"),
                5,
                DurationType.MINUTES,
                ScheduledJobStatus.ACTIVE);

        AiJobRunner runner = new AiJobRunner(job, aiService, repo);
        runner.run();

        verify(aiService).generate("Repeat", AiProvider.GEMINI);
        verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void run_aiFailure_doesNotThrowAndClearsSecurityContext() {
        AiOrchestrationService aiService = mock(AiOrchestrationService.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);

        ScheduledJob job = new ScheduledJob(
                "job-3",
                "Fails",
                "sid-3",
                "carol",
                Instant.parse("2026-05-17T10:15:30Z"),
                0,
                DurationType.MINUTES,
                ScheduledJobStatus.ACTIVE);

        when(aiService.generate("Fails", AiProvider.GEMINI)).thenThrow(new RuntimeException("boom"));

        AiJobRunner runner = new AiJobRunner(job, aiService, repo);
        runner.run();

        verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
