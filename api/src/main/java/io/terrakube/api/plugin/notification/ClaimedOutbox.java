package io.terrakube.api.plugin.notification;

import java.util.Date;

import io.terrakube.api.rs.notification.NotificationConfiguration;

record ClaimedOutbox(NotificationConfiguration configuration, String payload, int attemptCount, Date lastAttemptAt) {
}
