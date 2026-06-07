package sh.vork.scheduling.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.jadaptive.orm.DatabaseRepository;
import sh.vork.scheduling.domain.DurationType;
import sh.vork.scheduling.domain.InvocationType;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;
import sh.vork.scheduling.service.AiSchedulerService;

class SchedulerLifecycleManagerTest {

    private static final Instant START = Instant.parse("2026-05-17T10:15:30Z");

    private static ScheduledJob job(String id, InvocationType type, ScheduledJobStatus status) {
        return new ScheduledJob(id, "Job " + id, "p", "sid", "alice",
                type, START, 10, DurationType.MINUTES, 0L, 0L, null, null, null, 0, status);
    }

    @Test
    void onReady_schedulesWaitingJobsAndIgnoresPaused() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);
        AiSchedulerService schedulerService = mock(AiSchedulerService.class);
        when(schedulerService.computeEffectiveStart(any())).thenReturn(START);

        ScheduledJob waiting  = job("waiting-1",  InvocationType.REPEAT,   ScheduledJobStatus.WAITING);
        ScheduledJob paused   = job("paused-1",   InvocationType.REPEAT,   ScheduledJobStatus.PAUSED);

        when(repo.list(0, Integer.MAX_VALUE)).thenReturn(Stream.of(waiting, paused));

        SchedulerLifecycleManager manager = new SchedulerLifecycleManager(repo, schedulerService);
        manager.onReady();

        verify(schedulerService, times(1)).scheduleJob(any());
        verify(schedulerService, never()).scheduleJob(paused);
    }

    @Test
    void onReady_resetsStuckActiveJobsToWaitingThenSchedules() {
        @SuppressWarnings("unchecked")
        DatabaseRepository<ScheduledJob> repo = mock(DatabaseRepository.class);
        AiSchedulerService schedulerService = mock(AiSchedulerService.class);
        when(schedulerService.computeEffectiveStart(any())).thenReturn(START);

        ScheduledJob active        = job("active-1",         InvocationType.REPEAT, ScheduledJobStatus.ACTIVE);
        ScheduledJob awaitingInput = job("awaiting-input-1", InvocationType.REPEAT, ScheduledJobStatus.AWAITING_INPUT);

        when(repo.list(0, Integer.MAX_VALUE)).thenReturn(Stream.of(active, awaitingInput));

        SchedulerLifecycleManager manager = new SchedulerLifecycleManager(repo, schedulerService);
        manager.onReady();

        // Both stuck jobs should be reset to WAITING in repo
        ArgumentCaptor<ScheduledJob> repoSaved = ArgumentCaptor.forClass(ScheduledJob.class);
        verify(repo, times(2)).save(repoSaved.capture());
        repoSaved.getAllValues().forEach(j ->
                assertEquals(ScheduledJobStatus.WAITING, j.status(), "Expected WAITING after reset"));

        // Both should be rescheduled
        verify(schedulerService, times(2)).scheduleJob(any());
    }
}

