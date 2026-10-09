package io.terrakube.api.plugin.vcs.provider.github;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import io.terrakube.api.plugin.vcs.provider.exception.VcsTokenAcquisitionException;

// Classifies failures from GitHub's App/installation-token HTTP endpoints into a terminal
// (permanent, fail-the-job) or retryable (transient, worth backing off and trying again)
// VcsTokenAcquisitionException. GitHub-specific: it reads GitHub's own X-RateLimit-* headers
// and the 403-for-rate-limiting quirk, so a future non-GitHub provider gets its own classifier
// rather than sharing this one.
final class GitHubHttpErrors {

    private GitHubHttpErrors() {
    }

    static VcsTokenAcquisitionException classify(RestClientException e, String owner) {
        if (e instanceof HttpStatusCodeException httpError) {
            return classifyStatus(httpError, owner);
        }
        if (e instanceof ResourceAccessException) {
            // Timeout, connection refused, DNS failure - no response at all.
            return new VcsTokenAcquisitionException(
                    "Could not reach GitHub to obtain an access token (network error). Will retry.", e, true);
        }
        // Anything else unexpected from RestTemplate: fail open as retryable so a one-off client
        // oddity doesn't instantly terminal-fail the job - the caller's own dispatch-retry budget
        // still bounds how long this can be retried before it becomes terminal.
        return new VcsTokenAcquisitionException(
                "Unexpected error contacting GitHub to obtain an access token. Will retry.", e, true);
    }

    private static VcsTokenAcquisitionException classifyStatus(HttpStatusCodeException e, String owner) {
        int status = e.getStatusCode().value();
        switch (status) {
            case 401:
                return new VcsTokenAcquisitionException(
                        "GitHub rejected the App credentials (401 Unauthorized) - the App's private "
                                + "key or client id may be invalid or revoked.", e, false);
            case 404:
                // Deliberately ambiguous: GitHub returns 404 both when the App is not installed
                // for this org/repo and when it is installed but its repository selection does
                // not include this (possibly private) repository - it never distinguishes the
                // two in the response body.
                return new VcsTokenAcquisitionException(String.format(
                        "GitHub App cannot access '%s'. Either the GitHub App is not installed for "
                                + "this organization/repository, or it is installed but its repository "
                                + "selection does not include this (possibly private) repository. "
                                + "Check the App installation, repository access, and repository URL.",
                        owner), e, false);
            case 403:
                return classify403(e, owner);
            case 429:
                return new VcsTokenAcquisitionException(
                        "GitHub rate-limited this request (429 Too Many Requests). Will retry.", e, true,
                        parseRetryAfter(e.getResponseHeaders()));
            default:
                if (e.getStatusCode().is5xxServerError()) {
                    return new VcsTokenAcquisitionException(String.format(
                            "GitHub returned a server error (%d) while obtaining an access token. Will retry.",
                            status), e, true);
                }
                return new VcsTokenAcquisitionException(String.format(
                        "GitHub rejected the access-token request (%d).", status), e, false);
        }
    }

    // GitHub overloads 403 for two unrelated things: (a) the App genuinely lacks permission for
    // this org/repo/installation, and (b) GitHub's primary or secondary rate limiting (GitHub
    // sometimes signals rate limiting with 403 instead of 429). A 403 must never be
    // auto-classified as a permanent permissions failure - only when neither rate-limit signal
    // is present do we treat it as terminal.
    private static VcsTokenAcquisitionException classify403(HttpStatusCodeException e, String owner) {
        HttpHeaders headers = e.getResponseHeaders();
        Duration retryAfter = parseRetryAfter(headers);
        boolean rateLimitSignal = retryAfter != null
                || (headers != null && "0".equals(headers.getFirst("X-RateLimit-Remaining")));
        if (rateLimitSignal) {
            Duration hint = retryAfter != null ? retryAfter : parseRateLimitReset(headers);
            return new VcsTokenAcquisitionException(
                    "GitHub rate-limited this request (403, rate-limit signal present). Will retry.", e, true, hint);
        }
        return new VcsTokenAcquisitionException(String.format(
                "GitHub denied access while obtaining an App installation token for '%s' (403). "
                        + "The App may lack the required permissions for this organization/repository.",
                owner), e, false);
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

    // X-RateLimit-Reset is an epoch-seconds timestamp GitHub sends on primary rate limits.
    private static Duration parseRateLimitReset(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String value = headers.getFirst("X-RateLimit-Reset");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long resetEpochSeconds = Long.parseLong(value.trim());
            long delta = resetEpochSeconds - Instant.now().getEpochSecond();
            return delta > 0 ? Duration.ofSeconds(delta) : Duration.ZERO;
        } catch (NumberFormatException ignore) {
            return null;
        }
    }
}
