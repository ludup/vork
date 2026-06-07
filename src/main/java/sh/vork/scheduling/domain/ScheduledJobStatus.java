package sh.vork.scheduling.domain;

/** Lifecycle status of a scheduled job. */
public enum ScheduledJobStatus {
    /** Idle — ready and waiting for the next scheduled execution. */
    WAITING,
    /** Currently executing in a background thread. */
    ACTIVE,
    /** Suspended — a running turn requires user authorization before continuing. */
    AWAITING_INPUT,
    /** User-paused — will not execute until the user explicitly resumes it. */
    PAUSED,
    /** One-time job that has finished executing. */
    COMPLETED
}
