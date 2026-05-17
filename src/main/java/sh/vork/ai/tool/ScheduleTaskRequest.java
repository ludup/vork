package sh.vork.ai.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record ScheduleTaskRequest(
        @JsonProperty(required = true, value = "jobName")
        @JsonPropertyDescription("A short, human-readable name for the scheduled job.")
        String jobName,

        @JsonProperty(required = true, value = "backgroundPrompt")
        @JsonPropertyDescription("The explicit instruction set, fully detailed with all data context needed to execute this background task without human intervention later.")
        String backgroundPrompt,

        @JsonProperty(required = true, value = "startIsoTime")
        @JsonPropertyDescription("The absolute start time for the first execution in ISO-8601 format (e.g., '2026-05-17T16:00:00Z').")
        String startIsoTime,

        @JsonProperty(required = true, value = "repeatInterval")
        @JsonPropertyDescription("Interval length to repeat the execution. Pass 0 if this task should only run once.")
        long repeatInterval,

        @JsonProperty(required = true, value = "durationType")
        @JsonPropertyDescription("The period tracking type for the repeat interval length.")
        String durationType
) {}
