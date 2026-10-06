package io.terrakube.api.plugin.scheduler.trigger;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunTriggerEventBackoffTest {

    @Test
    void firstAttemptNeverExceedsTheInitialDelay() {
        for (int i = 0; i < 100; i++) {
            long delay = RunTriggerEventBackoff.nextDelayMillis(1, 5, 900);
            assertThat(delay).isBetween(0L, 5_000L);
        }
    }

    @RepeatedTest(50)
    void delayGrowsExponentiallyUpToTheCeiling() {
        // attempt 4: 5 * 2^3 = 40s uncapped, well under the 900s ceiling.
        long delay = RunTriggerEventBackoff.nextDelayMillis(4, 5, 900);
        assertThat(delay).isBetween(0L, 40_000L);
    }

    @RepeatedTest(50)
    void delayNeverExceedsTheConfiguredMaximum() {
        // attempt 20 would be 5 * 2^19 uncapped - must be clamped to the 900s ceiling.
        long delay = RunTriggerEventBackoff.nextDelayMillis(20, 5, 900);
        assertThat(delay).isBetween(0L, 900_000L);
    }

    @Test
    void zeroOrNegativeAttemptCountIsTreatedAsTheFirstAttempt() {
        for (int i = 0; i < 50; i++) {
            assertThat(RunTriggerEventBackoff.nextDelayMillis(0, 5, 900)).isBetween(0L, 5_000L);
            assertThat(RunTriggerEventBackoff.nextDelayMillis(-3, 5, 900)).isBetween(0L, 5_000L);
        }
    }

    @Test
    void aVeryLargeAttemptCountStillRespectsTheCeilingWithoutOverflowing() {
        long delay = RunTriggerEventBackoff.nextDelayMillis(Integer.MAX_VALUE, 5, 900);
        assertThat(delay).isBetween(0L, 900_000L);
    }

    /** A misconfigured non-positive bound must retry immediately, not throw. */
    @Test
    void zeroOrNegativeMaxSecondsRetriesImmediatelyInsteadOfThrowing() {
        assertThat(RunTriggerEventBackoff.nextDelayMillis(4, 5, 0)).isEqualTo(0L);
        assertThat(RunTriggerEventBackoff.nextDelayMillis(4, 5, -900)).isEqualTo(0L);
    }

    @Test
    void zeroOrNegativeInitialSecondsRetriesImmediatelyInsteadOfThrowing() {
        assertThat(RunTriggerEventBackoff.nextDelayMillis(1, 0, 900)).isEqualTo(0L);
        assertThat(RunTriggerEventBackoff.nextDelayMillis(1, -5, 900)).isEqualTo(0L);
    }
}
