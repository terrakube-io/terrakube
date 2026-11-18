package io.terrakube.api.plugin.scheduler.module;

import java.util.Date;
import java.util.concurrent.Callable;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import io.terrakube.api.repository.ModuleRepository;

@Component
public class ModuleRefreshMetrics {

    private final ModuleRepository moduleRepository;
    private final MeterRegistry meterRegistry;

    public ModuleRefreshMetrics(ModuleRepository moduleRepository, MeterRegistry meterRegistry) {
        this.moduleRepository = moduleRepository;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void registerGauges() {
        Gauge.builder("module.refresh.overdue.count", moduleRepository, repo -> repo.countOverdue(new Date()))
                .description("Number of modules due for a refresh that no replica has claimed yet")
                .register(meterRegistry);

        Gauge.builder("module.refresh.overdue.oldest.age.seconds", moduleRepository,
                        ModuleRefreshMetrics::oldestOverdueAgeSeconds)
                .description("Seconds since the longest-waiting module became due, 0 if none")
                .register(meterRegistry);
    }

    static double oldestOverdueAgeSeconds(ModuleRepository repo) {
        long now = System.currentTimeMillis();
        Date oldest = repo.findOldestOverdue(new Date(now));
        if (oldest == null) {
            return 0;
        }
        return Math.max(0, (now - oldest.getTime()) / 1000.0);
    }

    /** {@code cause} is {@code none} on success, otherwise one of ModuleRefreshWorker's failure causes. */
    public void recordResult(String outcome, String cause) {
        Counter.builder("module.refresh.result")
                .description("Module refreshes by outcome and failure cause")
                .tag("outcome", outcome)
                .tag("cause", cause)
                .register(meterRegistry)
                .increment();
    }

    public <T> T timeLsRemote(Callable<T> lsRemote) throws Exception {
        return Timer.builder("module.refresh.lsremote.duration")
                .description("Time spent reading the tags of a module repository")
                .register(meterRegistry)
                .recordCallable(lsRemote);
    }
}
