package sh.vork.scheduling.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import sh.vork.ai.entity.AiSession;
import com.jadaptive.orm.DatabaseRepository;
import sh.vork.scheduling.domain.DurationType;
import sh.vork.scheduling.domain.InvocationType;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;

class AiSchedulerServiceTest {

    private static ScheduledJob job(String id, InvocationType type, long repeatDuration,
                                    DurationType durationType, Instant start, ScheduledJobStatus status) {
        return new ScheduledJob(id, "Job " + id, "prompt", "sid", "alice",
                type, start, repeatDuration, durationType, 0L, 0L, null, null, null, status);
    }

    @Test
    void scheduleJob_oneShot_persistsAndSchedulesOnce() {
        ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);
        BackgroundOrchestrationEngine orchestrationEngine = mock(BackgroundOrchestrationEngine.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("rawtypes")
        ScheduledFuture future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Instant.class));

        AiSchedulerService service = new AiSchedulerService(scheduler, repo, orchestrationEngine, sessionRepo);

        Instant start = Instant.parse("2099-05-17T10:15:30Z"); // future so effectiveStart == start
        ScheduledJob jobIn = job("job-1", InvocationType.ONE_TIME, 0, DurationType.MINUTES, start,
                ScheduledJobStatus.WAITING);

        ScheduledJob out = service.scheduleJob(jobIn);

        assertEquals("job-1", out.id());
        verify(scheduler).schedule(any(Runnable.class), eq(start));

        ArgumentCaptor<ScheduledJob> saved = ArgumentCaptor.forClass(ScheduledJob.class);
        verify(repo).save(saved.capture());
        assertEquals("job-1", saved.getValue().id());
        assertEquals(ScheduledJobStatus.WAITING, saved.getValue().status());
    }

    @Test
    void scheduleJob_repeating_usesFixedRateWithMappedDuration() {
        ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);
        BackgroundOrchestrationEngine orchestrationEngine = mock(BackgroundOrchestrationEngine.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("rawtypes")
        ScheduledFuture future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler)
                .scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));

        AiSchedulerService service = new AiSchedulerService(scheduler, repo, orchestrationEngine, sessionRepo);

        Instant start = Instant.parse("2099-05-17T10:15:30Z");
        ScheduledJob jobIn = job("job-2", InvocationType.REPEAT, 2, DurationType.HOURS, start,
                ScheduledJobStatus.WAITING);

        service.scheduleJob(jobIn);

        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(start), eq(Duration.ofHours(2)));
    }

    @Test
    void scheduleJob_blankId_generatesIdAndDefaults() {
        ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);
        BackgroundOrchestrationEngine orchestrationEngine = mock(BackgroundOrchestrationEngine.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("rawtypes")
        ScheduledFuture future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Instant.class));

        AiSchedulerService service = new AiSchedulerService(scheduler, repo, orchestrationEngine, sessionRepo);

        // Null durationType and status — service should default them
        ScheduledJob jobIn = new ScheduledJob(" ", "Generated Job", "One shot", "sid-3", "charlie",
                InvocationType.ONE_TIME, null, 0, null, 0L, 0L, null, null, null, null);

        ScheduledJob out = service.scheduleJob(jobIn);

        assertNotNull(out.id());
        assertEquals(DurationType.MINUTES, out.durationType());
        assertEquals(ScheduledJobStatus.WAITING, out.status());

        ArgumentCaptor<ScheduledJob> saved = ArgumentCaptor.forClass(ScheduledJob.class);
        verify(repo).save(saved.capture());
        assertNotNull(saved.getValue().startTime());
    }

    @Test
    void pauseJob_cancelsFutureAndMarksPaused() {
        ThreadPoolTaskScheduler scheduler = mock(ThreadPoolTaskScheduler.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);
        BackgroundOrchestrationEngine orchestrationEngine = mock(BackgroundOrchestrationEngine.class);
        @SuppressWarnings("unchecked")
        DatabaseRepository<AiSession> sessionRepo = mock(DatabaseRepository.class);
        @SuppressWarnings("rawtypes")
        ScheduledFuture future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Instant.class));

        AiSchedulerService service = new AiSchedulerService(scheduler, repo, orchestrationEngine, sessionRepo);

        Instant start = Instant.parse("2099-05-17T10:15:30Z");
        ScheduledJob existing = job("job-3", InvocationType.ONE_TIME, 0, DurationType.MINUTES, start,
                ScheduledJobStatus.WAITING);

        service.scheduleJob(existing);
        when(repo.get("job-3")).thenReturn(existing);

        service.pauseJob("job-3");

        verify(future).cancel(false);

        ArgumentCaptor<ScheduledJob> saved = ArgumentCaptor.forClass(ScheduledJob.class);
        verify(repo, times(2)).save(saved.capture());
        ScheduledJob paused = saved.getAllValues().get(1);
        assertEquals(ScheduledJobStatus.PAUSED, paused.status());
    }
}
