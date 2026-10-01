package io.terrakube.api.plugin.scheduler.trigger;

import java.util.Date;
import java.util.List;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import io.terrakube.api.repository.RunTriggerEventRepository;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEvent;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEventStatus;

import lombok.extern.slf4j.Slf4j;

/**
 * Claims and processes every due {@code RunTriggerEvent} once a tick, and sweeps rows abandoned
 * by a replica that claimed one and died before recording a result. A Quartz {@code Job} never
 * gets its own AOP proxy, so every DB-mutating step is delegated to a real Spring-managed bean.
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class RunTriggerEventPollerJob implements Job {

    private final RunTriggerEventRepository runTriggerEventRepository;
    private final RunTriggerEventDispatchService runTriggerEventDispatchService;
    private final RunTriggerEventTransactions runTriggerEventTransactions;
    private final RunTriggerProperties properties;

    public RunTriggerEventPollerJob(RunTriggerEventRepository runTriggerEventRepository,
            RunTriggerEventDispatchService runTriggerEventDispatchService,
            RunTriggerEventTransactions runTriggerEventTransactions,
            RunTriggerProperties properties) {
        this.runTriggerEventRepository = runTriggerEventRepository;
        this.runTriggerEventDispatchService = runTriggerEventDispatchService;
        this.runTriggerEventTransactions = runTriggerEventTransactions;
        this.properties = properties;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        if (!properties.isEnabled() || !properties.isEventWorkerEnabled()) {
            // Events keep accumulating; only claiming stops until the worker is turned back on.
            return;
        }

        long leaseMillis = properties.getEventLeaseSeconds() * 1000L;
        runTriggerEventTransactions.sweepStuckProcessingRows(
                new Date(System.currentTimeMillis() - leaseMillis), properties.getEventMaxAttempts());
        processDueEvents();
    }

    private void processDueEvents() {
        List<RunTriggerEvent> due = runTriggerEventRepository.findDueForProcessing(RunTriggerEventStatus.PENDING,
                new Date(), PageRequest.of(0, properties.getEventPollerBatchSize()));

        for (RunTriggerEvent event : due) {
            // process() never throws: one event's problem must not stop the rest of the batch.
            runTriggerEventDispatchService.process(event.getId());
        }
        if (!due.isEmpty()) {
            log.info("Run trigger event poller processed {} due row(s)", due.size());
        }
    }
}
