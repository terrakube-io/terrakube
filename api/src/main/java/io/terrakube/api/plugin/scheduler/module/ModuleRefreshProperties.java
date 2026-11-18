package io.terrakube.api.plugin.scheduler.module;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.Date;
import java.util.function.DoubleSupplier;

@Slf4j
@Component
@Validated
@Getter
@Setter
@PropertySource(value = "classpath:application.properties", ignoreResourceNotFound = true)
@PropertySource(value = "classpath:application-${spring.profiles.active}.properties", ignoreResourceNotFound = true)
@ConfigurationProperties(prefix = "io.terrakube.api.module.refresh")
public class ModuleRefreshProperties implements EnvironmentAware {

    static final String LEGACY_INTERVAL_PROPERTY = "io.terrakube.api.module.refresh.interval";
    static final String LEGACY_INTERVAL_ENV = "ModuleRefreshInterval";
    // A Git read times out after 30 s, and an ls-remote can wait on connect and read.
    private static final int SECONDS_PER_REFRESH = 2 * 30;

    /** Minutes between refreshes of a module whose last new tag is at most activeWindowDays old. */
    @Positive
    private int activeIntervalMinutes = 15;
    /** Minutes between refreshes of a module whose last new tag is at most idleWindowDays old, or unknown. */
    @Positive
    private int idleIntervalMinutes = 60;
    /** Minutes between refreshes of a module whose last new tag is older than idleWindowDays. */
    @Positive
    private int dormantIntervalMinutes = 360;
    @Positive
    private int activeWindowDays = 30;
    @Positive
    private int idleWindowDays = 90;
    /** Minutes until a module whose last refresh failed is tried again. */
    @Positive
    private int errorIntervalMinutes = 60;
    /** Each interval is moved by up to this fraction either way, so modules do not refresh in lockstep. */
    @DecimalMin("0")
    @DecimalMax("0.5")
    private double jitter = 0.15;
    /** How long a claimed module stays with the replica that claimed it before another one may take it. */
    @Positive
    private int leaseMinutes = 5;
    @Valid
    private Executor executor = new Executor();
    private Webhook webhook = new Webhook();

    // The removed fixed interval in seconds, when an operator still sets it.
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Integer legacyIntervalSeconds;

    @Getter
    @Setter
    public static class Executor {
        @Positive
        private int poolSize = 4;
        @Positive
        private int queueCapacity = 16;
    }

    @Getter
    @Setter
    public static class Webhook {
        /**
         * Register the repository webhook (with tag events) for modules that have a GitHub, GitLab or Azure DevOps
         * connection. Tags received on a webhook refresh modules either way.
         */
        private boolean enabled = false;
    }

    // Read directly: application.properties no longer maps the old environment variable onto the property.
    @Override
    public void setEnvironment(Environment environment) {
        String legacy = environment.getProperty(LEGACY_INTERVAL_PROPERTY, environment.getProperty(LEGACY_INTERVAL_ENV));
        if (legacy == null || legacy.isBlank()) {
            return;
        }
        try {
            int seconds = Integer.parseInt(legacy.trim());
            legacyIntervalSeconds = seconds > 0 ? seconds : null;
        } catch (NumberFormatException e) {
            log.warn("Ignoring module refresh interval '{}': it is not a number of seconds", legacy);
        }
    }

    @PostConstruct
    public void checkSettings() {
        if (legacyIntervalSeconds != null) {
            log.warn("{} ({}) is deprecated: modules that refresh successfully are re-read every {} s instead of on "
                    + "the activity-based intervals. Remove it to use active-interval-minutes, idle-interval-minutes "
                    + "and dormant-interval-minutes.", LEGACY_INTERVAL_PROPERTY, LEGACY_INTERVAL_ENV, legacyIntervalSeconds);
        }
        // Every queued refresh must be able to finish before its lease runs out, or another replica claims the
        // module again while the first one still works on it.
        int queuedRounds = (executor.queueCapacity + executor.poolSize - 1) / executor.poolSize;
        int minLeaseMinutes = (queuedRounds + 1) * SECONDS_PER_REFRESH / 60;
        if (leaseMinutes < minLeaseMinutes) {
            log.warn("Module refresh lease of {} min is too short for a queue of {} on {} threads, using {} min",
                    leaseMinutes, executor.queueCapacity, executor.poolSize, minLeaseMinutes);
            leaseMinutes = minLeaseMinutes;
        }
    }

    /**
     * When a module should be refreshed next, truncated to whole seconds. A failure waits errorIntervalMinutes.
     * Otherwise the interval follows how recently a new tag showed up, and an unknown age counts as idle.
     */
    public Date nextRefresh(Date now, Date lastTagDetectedAt, boolean failed, DoubleSupplier random) {
        long baseMillis;
        if (failed) {
            baseMillis = minutes(errorIntervalMinutes);
        } else if (legacyIntervalSeconds != null) {
            baseMillis = legacyIntervalSeconds * 1000L;
        } else if (lastTagDetectedAt == null) {
            baseMillis = minutes(idleIntervalMinutes);
        } else {
            long tagAgeMillis = now.getTime() - lastTagDetectedAt.getTime();
            if (tagAgeMillis <= days(activeWindowDays)) {
                baseMillis = minutes(activeIntervalMinutes);
            } else if (tagAgeMillis <= days(idleWindowDays)) {
                baseMillis = minutes(idleIntervalMinutes);
            } else {
                baseMillis = minutes(dormantIntervalMinutes);
            }
        }
        double factor = 1 + jitter * (2 * random.getAsDouble() - 1);
        return ModuleRefreshTransactions.toSeconds(now.getTime() + Math.round(baseMillis * factor));
    }

    private static long minutes(int value) {
        return value * 60_000L;
    }

    private static long days(int value) {
        return value * 86_400_000L;
    }
}
