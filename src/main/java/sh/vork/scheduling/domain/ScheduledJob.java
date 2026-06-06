package sh.vork.scheduling.domain;

import java.time.Instant;

import com.jadaptive.orm.DatabaseEntity;

/**
 * Persistent scheduled background AI job definition.
 *
 * <p>Jobs are owned by a user ({@code userId}) and can be triggered manually,
 * run once at a scheduled time, or repeat on a fixed interval.  An optional
 * agent template, provider, and model can be pinned to override the defaults
 * used when the job executes.
 */
public record ScheduledJob(
        String id,
        String name,
        String aiPrompt,
        String sessionUuid,
        String userId,                 // user primary key (VorkUser.uuid == username)
        InvocationType invocationType, // MANUAL | ONE_TIME | REPEAT
        Instant startTime,
        long repeatDuration,
        DurationType durationType,
        long lastExecutionTime,        // epoch ms of last run start, 0 = never
        long nextExecutionTime,        // epoch ms of next scheduled run, 0 = N/A (MANUAL or completed ONE_TIME)
        String agentTemplateId,        // optional — pinned agent template UUID
        String provider,               // optional — AiProvider name override
        String modelId,                // optional — model ID override
        ScheduledJobStatus status
) implements DatabaseEntity {

    @Override
    public String uuid() {
        return id;
    }
}

