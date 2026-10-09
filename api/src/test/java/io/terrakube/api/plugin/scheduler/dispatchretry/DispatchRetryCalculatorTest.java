package io.terrakube.api.plugin.scheduler.dispatchretry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.terrakube.api.rs.job.Job;

class DispatchRetryCalculatorTest {

    private DispatchRetryProperties properties;
    private DispatchRetryCalculator calculator;
    private Date now;

    @BeforeEach
    void setup() {
        properties = new DispatchRetryProperties();
        properties.setInitialBackoffSeconds(10);
        properties.setMaxBackoffSeconds(80);
        properties.setMaxAttempts(5);
        properties.setMaxElapsedMinutes(60);
        calculator = new DispatchRetryCalculator(properties);
        now = new Date();
    }

    private Job freshJob() {
        return new Job();
    }

    @Test
    void firstFailure_withNoHint_usesInitialBackoff() {
        DispatchRetryDecision decision = calculator.decide(freshJob(), now, null);

        assertThat(decision.exhausted()).isFalse();
        assertThat(decision.newFailureCount()).isEqualTo(1);
        assertThat(decision.firstFailureAt()).isEqualTo(now);
        assertThat(decision.nextRetryAt()).isEqualTo(Date.from(now.toInstant().plusSeconds(10)));
    }

    @Test
    void firstFailure_setsFirstFailureAtToNow() {
        Job job = freshJob();

        DispatchRetryDecision decision = calculator.decide(job, now, null);

        assertThat(decision.firstFailureAt()).isEqualTo(now);
    }

    @Test
    void subsequentFailure_carriesForwardTheExistingFirstFailureAt() {
        Job job = freshJob();
        Date originalFirstFailure = Date.from(now.toInstant().minusSeconds(120));
        job.setDispatchFirstFailureAt(originalFirstFailure);
        job.setDispatchFailureCount(1);

        DispatchRetryDecision decision = calculator.decide(job, now, null);

        assertThat(decision.firstFailureAt()).isEqualTo(originalFirstFailure);
        assertThat(decision.newFailureCount()).isEqualTo(2);
    }

    @Test
    void backoffDoublesEachAttemptUntilCapped() {
        Job job = freshJob();
        job.setDispatchFailureCount(1); // attempt 2
        Date attempt2 = calculator.decide(job, now, null).nextRetryAt();
        assertThat(attempt2).isEqualTo(Date.from(now.toInstant().plusSeconds(20)));

        job.setDispatchFailureCount(2); // attempt 3
        Date attempt3 = calculator.decide(job, now, null).nextRetryAt();
        assertThat(attempt3).isEqualTo(Date.from(now.toInstant().plusSeconds(40)));

        job.setDispatchFailureCount(3); // attempt 4 -> would be 80s, exactly the cap
        Date attempt4 = calculator.decide(job, now, null).nextRetryAt();
        assertThat(attempt4).isEqualTo(Date.from(now.toInstant().plusSeconds(80)));
    }

    @Test
    void aSmallerProviderHintOverridesTheComputedExponentialValue() {
        Job job = freshJob();
        job.setDispatchFailureCount(2); // attempt 3 would compute 40s

        DispatchRetryDecision decision = calculator.decide(job, now, Duration.ofSeconds(5));

        assertThat(decision.nextRetryAt()).isEqualTo(Date.from(now.toInstant().plusSeconds(5)));
    }

    @Test
    void aLargerProviderHintIsStillCappedAtMaxBackoffSeconds() {
        Job job = freshJob();

        DispatchRetryDecision decision = calculator.decide(job, now, Duration.ofSeconds(10_000));

        assertThat(decision.nextRetryAt()).isEqualTo(Date.from(now.toInstant().plusSeconds(80)));
    }

    @Test
    void attemptCountCapTriggersExhaustion() {
        Job job = freshJob();
        job.setDispatchFailureCount(properties.getMaxAttempts() - 1); // this failure reaches maxAttempts
        job.setDispatchFirstFailureAt(now);

        DispatchRetryDecision decision = calculator.decide(job, now, null);

        assertThat(decision.exhausted()).isTrue();
        assertThat(decision.nextRetryAt()).isNull();
    }

    @Test
    void elapsedTimeCapTriggersExhaustionIndependentlyOfAttemptCount() {
        Job job = freshJob();
        job.setDispatchFailureCount(0);
        job.setDispatchFirstFailureAt(Date.from(now.toInstant().minusSeconds(3600 + 60)));

        DispatchRetryDecision decision = calculator.decide(job, now, null);

        assertThat(decision.exhausted()).isTrue();
    }

    @Test
    void neitherCapTripped_staysUnexhausted() {
        Job job = freshJob();
        job.setDispatchFailureCount(1);
        job.setDispatchFirstFailureAt(Date.from(now.toInstant().minusSeconds(30)));

        DispatchRetryDecision decision = calculator.decide(job, now, null);

        assertThat(decision.exhausted()).isFalse();
    }
}
