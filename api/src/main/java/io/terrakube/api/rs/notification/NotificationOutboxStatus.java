package io.terrakube.api.rs.notification;

public enum NotificationOutboxStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,
    // Terminal, like SENT/FAILED, but nothing was ever delivered: by the time this row was
    // claimed for dispatch the job had already moved past the status the payload was rendered
    // for (typically a transient "failed" that a lost executor-dispatch response caused, which
    // the executor's own status callback then corrected). Sending it would be a false alarm.
    SKIPPED
}
