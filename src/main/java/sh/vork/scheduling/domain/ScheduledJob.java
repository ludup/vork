package sh.vork.scheduling.domain;

import java.time.Instant;

import com.jadaptive.orm.DatabaseEntity;

/**
 * Persistent scheduled background AI job definition.
 */
public record ScheduledJob(
        String id,
    String name,
        String aiPrompt,
        String sessionUuid,
        String username,
        Instant startTime,
        long repeatDuration,
        DurationType durationType,
        ScheduledJobStatus status
) implements DatabaseEntity {

    @Override
    public String uuid() {
        return id;
    }
}
