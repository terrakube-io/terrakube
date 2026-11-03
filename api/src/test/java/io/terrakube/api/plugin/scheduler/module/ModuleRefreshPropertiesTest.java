package io.terrakube.api.plugin.scheduler.module;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleRefreshPropertiesTest {

    // Whole seconds, as the poller passes it.
    private static final Date NOW = new Date(1_800_000_000_000L);

    private final ModuleRefreshProperties properties = new ModuleRefreshProperties();

    private long minutesAfterNow(Date next) {
        return TimeUnit.MILLISECONDS.toMinutes(next.getTime() - NOW.getTime());
    }

    private Date daysAgo(int days) {
        return new Date(NOW.getTime() - TimeUnit.DAYS.toMillis(days));
    }

    @Test
    void intervalFollowsTheAgeOfTheLastNewTag() {
        assertThat(minutesAfterNow(properties.nextRefresh(NOW, daysAgo(0), false, () -> 0.5))).isEqualTo(15);
        assertThat(minutesAfterNow(properties.nextRefresh(NOW, daysAgo(30), false, () -> 0.5))).isEqualTo(15);
        assertThat(minutesAfterNow(properties.nextRefresh(NOW, daysAgo(31), false, () -> 0.5))).isEqualTo(60);
        assertThat(minutesAfterNow(properties.nextRefresh(NOW, daysAgo(90), false, () -> 0.5))).isEqualTo(60);
        assertThat(minutesAfterNow(properties.nextRefresh(NOW, daysAgo(91), false, () -> 0.5))).isEqualTo(360);
    }

    @Test
    void unknownTagAgeCountsAsIdle() {
        assertThat(minutesAfterNow(properties.nextRefresh(NOW, null, false, () -> 0.5))).isEqualTo(60);
    }

    @Test
    void failureUsesTheErrorIntervalWhateverTheTagAge() {
        properties.setErrorIntervalMinutes(45);

        assertThat(minutesAfterNow(properties.nextRefresh(NOW, daysAgo(0), true, () -> 0.5))).isEqualTo(45);
        assertThat(minutesAfterNow(properties.nextRefresh(NOW, null, true, () -> 0.5))).isEqualTo(45);
    }

    @Test
    void jitterStaysWithinTheConfiguredFraction() {
        long lowest = properties.nextRefresh(NOW, daysAgo(0), false, () -> 0.0).getTime() - NOW.getTime();
        long highest = properties.nextRefresh(NOW, daysAgo(0), false, () -> 0.999999).getTime() - NOW.getTime();

        assertThat(lowest).isEqualTo(TimeUnit.SECONDS.toMillis(765)); // 15 min x 0.85
        assertThat(highest).isBetween(TimeUnit.SECONDS.toMillis(1034), TimeUnit.SECONDS.toMillis(1035)); // x 1.15
    }

    @Test
    void resultIsTruncatedToWholeSeconds() {
        Date next = properties.nextRefresh(new Date(NOW.getTime() + 999), null, false, () -> 0.123456);

        assertThat(next.getTime() % 1000).isZero();
    }

    @Test
    void legacyIntervalFromTheEnvironmentVariableReplacesTheSuccessBuckets() {
        properties.setEnvironment(new MockEnvironment().withProperty("ModuleRefreshInterval", "300"));
        properties.checkSettings();

        assertThat(minutesAfterNow(properties.nextRefresh(NOW, daysAgo(200), false, () -> 0.5))).isEqualTo(5);
        assertThat(minutesAfterNow(properties.nextRefresh(NOW, daysAgo(200), true, () -> 0.5))).isEqualTo(60);
    }

    @Test
    void legacyPropertyWinsOverTheEnvironmentVariable() {
        properties.setEnvironment(new MockEnvironment()
                .withProperty("io.terrakube.api.module.refresh.interval", "120")
                .withProperty("ModuleRefreshInterval", "300"));

        assertThat(minutesAfterNow(properties.nextRefresh(NOW, null, false, () -> 0.5))).isEqualTo(2);
    }

    @Test
    void withoutLegacyIntervalTheBucketsApply() {
        properties.setEnvironment(new MockEnvironment());

        assertThat(minutesAfterNow(properties.nextRefresh(NOW, null, false, () -> 0.5))).isEqualTo(60);
    }

    @Test
    void leaseShorterThanTheQueueNeedsIsRaised() {
        properties.getExecutor().setPoolSize(2);
        properties.getExecutor().setQueueCapacity(20);
        properties.setLeaseMinutes(5);

        properties.checkSettings();

        assertThat(properties.getLeaseMinutes()).isEqualTo(11);
    }

    @Test
    void defaultLeaseFitsTheDefaultQueue() {
        properties.checkSettings();

        assertThat(properties.getLeaseMinutes()).isEqualTo(5);
    }
}
