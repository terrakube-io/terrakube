package io.terrakube.api.plugin.notification.sender;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

class HttpDeliveryErrorsTest {

    @Test
    void tooManyRequestsIsRetryable() {
        HttpClientErrorException e = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);

        NotificationDeliveryException result = HttpDeliveryErrors.fromStatus("Slack", e);

        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getRetryAfter()).isNull();
    }

    @Test
    void serverErrorIsRetryable() {
        HttpServerErrorException e = HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE,
                "Service Unavailable", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);

        NotificationDeliveryException result = HttpDeliveryErrors.fromStatus("Teams", e);

        assertThat(result.isRetryable()).isTrue();
    }

    @Test
    void otherClientErrorsAreNotRetryable() {
        for (HttpStatus status : new HttpStatus[] { HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED,
                HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND, HttpStatus.GONE }) {
            HttpClientErrorException e = HttpClientErrorException.create(status, status.getReasonPhrase(), new HttpHeaders(),
                    new byte[0], StandardCharsets.UTF_8);

            NotificationDeliveryException result = HttpDeliveryErrors.fromStatus("Webhook", e);

            assertThat(result.isRetryable()).as("status %s should be terminal", status).isFalse();
            assertThat(result.getRetryAfter()).isNull();
        }
    }

    @Test
    void parsesDeltaSecondsRetryAfterHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "30");
        HttpClientErrorException e = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                headers, new byte[0], StandardCharsets.UTF_8);

        NotificationDeliveryException result = HttpDeliveryErrors.fromStatus("Slack", e);

        assertThat(result.getRetryAfter()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void parsesHttpDateRetryAfterHeader() {
        ZonedDateTime target = ZonedDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(2).withNano(0);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, target.format(DateTimeFormatter.RFC_1123_DATE_TIME));
        HttpServerErrorException e = HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE,
                "Service Unavailable", headers, new byte[0], StandardCharsets.UTF_8);

        NotificationDeliveryException result = HttpDeliveryErrors.fromStatus("Teams", e);

        assertThat(result.getRetryAfter()).isNotNull();
        assertThat(result.getRetryAfter().toSeconds()).isBetween(100L, 125L);
    }

    @Test
    void malformedRetryAfterHeaderIsIgnored() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "not-a-valid-value");
        HttpClientErrorException e = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                headers, new byte[0], StandardCharsets.UTF_8);

        NotificationDeliveryException result = HttpDeliveryErrors.fromStatus("Slack", e);

        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getRetryAfter()).isNull();
    }

    @Test
    void networkErrorsAreRetryable() {
        ResourceAccessException e = new ResourceAccessException("connect timed out");

        NotificationDeliveryException result = HttpDeliveryErrors.fromNetworkError("Webhook", e);

        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getRetryAfter()).isNull();
    }
}
