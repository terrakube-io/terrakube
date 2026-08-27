package io.terrakube.api.plugin.scheduler;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class QuartzMetrics {

    private final Scheduler scheduler;
    private final MeterRegistry meterRegistry;

    public QuartzMetrics(Scheduler scheduler, MeterRegistry meterRegistry) {
        this.scheduler = scheduler;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void registerGauge() {
        Gauge.builder("quartz.jobs.executing", scheduler, QuartzMetrics::currentlyExecutingCount)
                .description("Number of Quartz jobs currently executing across this scheduler instance")
                .register(meterRegistry);
    }

    static double currentlyExecutingCount(Scheduler scheduler) {
        try {
            return scheduler.getCurrentlyExecutingJobs().size();
        } catch (SchedulerException e) {
            log.warn("Could not read currently executing Quartz jobs: {}", e.getMessage());
            return -1;
        }
    }
}
