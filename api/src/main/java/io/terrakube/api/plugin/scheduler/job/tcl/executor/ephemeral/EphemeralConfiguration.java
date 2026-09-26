package io.terrakube.api.plugin.scheduler.job.tcl.executor.ephemeral;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.PropertySources;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;

@Component
@Validated
@Getter
@Setter
@PropertySources({
        @PropertySource(value = "classpath:application.properties", ignoreResourceNotFound = true),
        @PropertySource(value = "classpath:application-${spring.profiles.active}.properties", ignoreResourceNotFound = true)
})
@ConfigurationProperties(prefix = "io.terrakube.executor.ephemeral")
public class EphemeralConfiguration {

    private String namespace;
    private String image;
    private List<String> secret;
    private Map<String, String> nodeSelector;
    /** Kubernetes rejects a Job whose activeDeadlineSeconds is not greater than zero. */
    @Positive
    private Long activeDeadlineSeconds;
    /** Zero is meaningful here: never retry a failed pod. */
    @PositiveOrZero
    private Integer backoffLimit;
    @PositiveOrZero
    private Integer ttlSecondsAfterFinished = 30;
    /**
     * Zero is legal but means an immediate SIGKILL, leaving terraform no time to release its
     * state lock. Defaults to 60 (double the measured ~28s JVM/library shutdown budget) rather
     * than inheriting Kubernetes' 30s pod default, which leaves no margin.
     */
    @PositiveOrZero
    private Long terminationGracePeriodSeconds = 60L;
}
