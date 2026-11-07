package io.terrakube.api.plugin.scheduler.module;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import io.terrakube.api.repository.ModuleRepository;

import lombok.extern.slf4j.Slf4j;

// The single place modules are refreshed from. Every replica polls on its own (a Spring @Scheduled task,
// not a clustered Quartz job), so a replica of an older release sharing the database cannot stall it.
// Each poll claims as many due modules as this replica's executor has room for and queues one refresh per
// module. A claim is a conditional update that sets next_refresh to the lease expiry, so replicas polling
// at the same time never take the same module, and a replica that dies mid-refresh only delays that module
// until the lease runs out. Writes go through ModuleRefreshTransactions.
@Slf4j
@Component
@ConditionalOnProperty(name = "io.terrakube.api.module.refresh.poller-enabled", matchIfMissing = true)
public class ModuleRefreshPoller {

    private final ModuleRepository moduleRepository;
    private final ModuleRefreshTransactions moduleRefreshTransactions;
    private final ModuleRefreshWorker moduleRefreshWorker;
    private final ThreadPoolTaskExecutor moduleRefreshExecutor;
    private final ModuleRefreshProperties moduleRefreshProperties;

    public ModuleRefreshPoller(ModuleRepository moduleRepository,
            ModuleRefreshTransactions moduleRefreshTransactions, ModuleRefreshWorker moduleRefreshWorker,
            @Qualifier("moduleRefreshExecutor") ThreadPoolTaskExecutor moduleRefreshExecutor,
            ModuleRefreshProperties moduleRefreshProperties) {
        this.moduleRepository = moduleRepository;
        this.moduleRefreshTransactions = moduleRefreshTransactions;
        this.moduleRefreshWorker = moduleRefreshWorker;
        this.moduleRefreshExecutor = moduleRefreshExecutor;
        this.moduleRefreshProperties = moduleRefreshProperties;
    }

    // Runs on Spring's scheduler pool (spring.task.scheduling.pool.size), so a poll only claims and queues;
    // the refreshes run on moduleRefreshExecutor. Polling every 2 s keeps the queue topped up as threads free.
    @Scheduled(fixedDelay = 2_000, initialDelay = 30_000)
    public void poll() {
        int free = moduleRefreshExecutor.getMaxPoolSize() - moduleRefreshExecutor.getActiveCount()
                + moduleRefreshExecutor.getThreadPoolExecutor().getQueue().remainingCapacity();
        if (free <= 0) {
            return;
        }
        int claimed = claimAndQueue(free);
        if (claimed > 0) {
            log.info("Module refresh poller claimed {} module(s)", claimed);
        }
    }

    /** @return how many modules were claimed and queued */
    private int claimAndQueue(int free) {
        Date now = ModuleRefreshTransactions.toSeconds(System.currentTimeMillis());
        // Writes match the lease by equality and dates are whole seconds, so two replicas claiming the same
        // module in the same second (after a sync made it due again) would share a lease. A random extra
        // 0-59 s makes that a 1-in-60 coincidence on top of a same-second one.
        Date lease = new Date(now.getTime() + moduleRefreshProperties.getLeaseMinutes() * 60_000L
                + ThreadLocalRandom.current().nextInt(60) * 1000L);
        List<UUID> claimed = moduleRepository.findDueIds(now, PageRequest.of(0, free)).stream()
                .filter(id -> moduleRefreshTransactions.claim(id, lease, now))
                .toList();

        for (int i = 0; i < claimed.size(); i++) {
            UUID moduleId = claimed.get(i);
            try {
                moduleRefreshExecutor.execute(() -> refresh(moduleId, lease));
            } catch (RejectedExecutionException e) {
                // Only if something else filled the queue since free was computed. Hand this module and
                // every one after it back, so they are due again on the next tick instead of waiting
                // for their leases to run out.
                List<UUID> unqueued = claimed.subList(i, claimed.size());
                moduleRefreshTransactions.releaseLeases(unqueued, lease);
                log.warn("Module refresh executor is full, released {} claimed module(s) for the next tick",
                        unqueued.size());
                return 0;
            }
        }
        return claimed.size();
    }

    private void refresh(UUID moduleId, Date lease) {
        try {
            moduleRefreshWorker.refresh(moduleId, lease);
        } catch (RuntimeException e) {
            // Recording the result failed (database). The lease runs out and the module is claimed again.
            log.error("Failed to record the refresh of module {}, error {}", moduleId, e.getMessage());
        }
    }
}
