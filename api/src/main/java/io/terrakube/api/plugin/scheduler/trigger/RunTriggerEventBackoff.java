package io.terrakube.api.plugin.scheduler.trigger;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with full jitter for retrying a failed {@code RunTriggerEvent}. A pure
 * function of the attempt number rather than a stateful counter, so it needs no mocking to test
 * and nothing to reset between events.
 */
final class RunTriggerEventBackoff {

    private RunTriggerEventBackoff() {
    }

    /**
     * Delay before the next attempt, in milliseconds.
     *
     * @param attemptCount    attempts made so far (the one that just failed), at least 1
     * @param initialSeconds  delay for the first retry, before jitter
     * @param maxSeconds      ceiling the exponential growth never exceeds
     */
    static long nextDelayMillis(int attemptCount, int initialSeconds, int maxSeconds) {
        int attempts = Math.max(attemptCount, 1);
        // Guard against overflow before maxSeconds caps it anyway.
        long exponential = attempts >= 32 ? Long.MAX_VALUE : (long) initialSeconds << (attempts - 1);
        long cappedSeconds = Math.min(exponential, maxSeconds);
        // Full jitter: uniform random delay between 0 and the capped value, so a correlated
        // batch of failures doesn't all retry on the same handful of backoff tiers at once.
        long jitteredSeconds = ThreadLocalRandom.current().nextLong(cappedSeconds + 1);
        return jitteredSeconds * 1000L;
    }
}
