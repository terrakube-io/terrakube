package io.terrakube.api.plugin.scheduler.module;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import io.terrakube.api.repository.ModuleRepository;

import lombok.extern.slf4j.Slf4j;

// The single place modules are refreshed from. Every tick (clustered, so one replica at a time), it
// claims as many due modules as this replica's executor has room for and queues one refresh per module,
// and keeps doing so for up to runMillis as threads free up while due modules remain.
// A claim sets next_refresh to the lease expiry, so a replica that dies mid-refresh only delays that
// module until the lease runs out. As with RepoWebhookDeliveryPollerJob, Quartz gives this class no AOP
// proxy, so every write goes through ModuleRefreshTransactions.
@Slf4j
@Component
@DisallowConcurrentExecution
public class ModuleRefreshPollerJob implements Job {

    private final ModuleRepository moduleRepository;
    private final ModuleRefreshTransactions moduleRefreshTransactions;
    private final ModuleRefreshWorker moduleRefreshWorker;
    private final ThreadPoolTaskExecutor moduleRefreshExecutor;
    private final ModuleRefreshProperties moduleRefreshProperties;
    // The trigger fires every minute; a run ends after this so it never overlaps the next one.
    long runMillis = 50_000;
    long pauseMillis = 1_000;

    public ModuleRefreshPollerJob(ModuleRepository moduleRepository,
            ModuleRefreshTransactions moduleRefreshTransactions, ModuleRefreshWorker moduleRefreshWorker,
            @Qualifier("moduleRefreshExecutor") ThreadPoolTaskExecutor moduleRefreshExecutor,
            ModuleRefreshProperties moduleRefreshProperties) {
        this.moduleRepository = moduleRepository;
        this.moduleRefreshTransactions = moduleRefreshTransactions;
        this.moduleRefreshWorker = moduleRefreshWorker;
        this.moduleRefreshExecutor = moduleRefreshExecutor;
        this.moduleRefreshProperties = moduleRefreshProperties;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        // A full pool only takes poolSize + queueCapacity modules at once. Keep claiming as threads free up
        // while due rows remain, and stop in time for the next tick to take over.
        long deadline = System.currentTimeMillis() + runMillis;
        int total = 0;
        while (true) {
            int free = moduleRefreshExecutor.getMaxPoolSize() - moduleRefreshExecutor.getActiveCount()
                    + moduleRefreshExecutor.getThreadPoolExecutor().getQueue().remainingCapacity();
            if (free > 0) {
                int requested = free;
                int claimed = claimAndQueue(requested);
                total += claimed;
                if (claimed < requested) {
                    break;
                }
            }
            if (System.currentTimeMillis() + pauseMillis >= deadline) {
                break;
            }
            try {
                Thread.sleep(pauseMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (total > 0) {
            log.info("Module refresh poller claimed {} module(s)", total);
        }
    }

    /** @return how many modules were claimed and queued; fewer than {@code free} means none are left due */
    private int claimAndQueue(int free) {
        Date now = ModuleRefreshTransactions.toSeconds(System.currentTimeMillis());
        Date lease = new Date(now.getTime() + moduleRefreshProperties.getLeaseMinutes() * 60_000L);
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
