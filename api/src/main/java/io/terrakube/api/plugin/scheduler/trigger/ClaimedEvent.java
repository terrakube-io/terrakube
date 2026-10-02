package io.terrakube.api.plugin.scheduler.trigger;

import java.util.Date;

/** What {@link RunTriggerEventTransactions#claim} hands back once a row is successfully claimed. */
record ClaimedEvent(int jobId, int attemptCount, Date lastAttemptAt) {
}
