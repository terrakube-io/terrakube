package io.terrakube.api.plugin.notification.sender;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

// Shared HTTP failure classification for the Slack/Teams/webhook senders: a 429 or 5xx is the
// destination (or an intermediary) saying "try again later", so it's retryable and may carry a
// Retry-After hint; any other 4xx (bad payload, bad/expired webhook URL, auth rejected, etc.) is
// the destination permanently rejecting this request, so retrying it would just fail the same way
// forever. Network-level failures (timeout, connection refused, DNS) carry no status code at all
// and are treated as retryable, since they're usually transient.
final class HttpDeliveryErrors {

    private HttpDeliveryErrors() {
    }

    static NotificationDeliveryException fromStatus(String channelLabel, HttpStatusCodeException e) {
        HttpStatusCode status = e.getStatusCode();
        boolean retryable = status.value() == 429 || status.is5xxServerError();
        String message = channelLabel + " endpoint returned status " + status.value();
        if (!retryable) {
            return new NotificationDeliveryException(message, e, false);
        }
        return new NotificationDeliveryException(message, e, true, parseRetryAfter(e.getResponseHeaders()));
    }

    static NotificationDeliveryException fromNetworkError(String channelLabel, RestClientException e) {
        return new NotificationDeliveryException("Failed to deliver " + channelLabel + " notification: " + e.getMessage(),
                e, true);
    }

    private static Duration parseRetryAfter(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return null;
        }
        value = value.trim();
        try {
            long seconds = Long.parseLong(value);
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException notDeltaSeconds) {
            try {
                ZonedDateTime target = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
                Duration delay = Duration.between(ZonedDateTime.now(target.getZone()), target);
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (DateTimeParseException notHttpDate) {
                return null;
            }
        }
    }
}
