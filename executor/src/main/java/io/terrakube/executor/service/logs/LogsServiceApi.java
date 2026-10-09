package io.terrakube.executor.service.logs;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.job.Log;
import io.terrakube.client.model.organization.job.LogsRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

@Service
@Slf4j
@AllArgsConstructor
@ConditionalOnProperty(name = "io.executor.log-via-api", havingValue = "true", matchIfMissing = false)
public class LogsServiceApi implements ProcessLogs {
    private TerrakubeClient terrakubeClient;
    private final LinkedBlockingDeque<Log> logQueue = new LinkedBlockingDeque<>();
    // Held while a batch is in flight, so flush() can't return while a scheduled batch it never
    // saw is still being sent.
    private final ReentrantLock sendLock = new ReentrantLock();

    @Override
    public void setupConsumerGroups(String jobId) {
        log.info("Setting up consumer groups for job {}", jobId);
        terrakubeClient.setupConsumerGroups(jobId);
    }

    @Override
    public void sendLogs(Integer jobId, String stepId, int lineNumber, String output) {
        Log logEntry = new Log();
        logEntry.setJobId(jobId);
        logEntry.setStepId(stepId);
        logEntry.setLineNumber(lineNumber);
        logEntry.setOutput(output);

        try {
            logQueue.put(logEntry);
        } catch (InterruptedException addLogException) {
            log.error("Failed to add log to queue", addLogException);
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void sendStructuredUpdate(Integer jobId, String stepId, String structuredJson) {
        // No-op: this transport (io.executor.log-via-api=true) has no Redis stream to push to.
        // Structured output still reaches the UI via the existing poll-based /context/v1 GET/POST
        // path (ApplyStructuredOutputService/PlanStructuredOutputService), unaffected by this -
        // it just doesn't get the new live-push latency improvement in this deployment mode.
    }

    // Best effort: if the API still refuses the batch after the client's retries, the lines are
    // requeued for the scheduler and this returns anyway rather than holding the step open.
    @Override
    public void flush() {
        sendLock.lock();
        try {
            sendQueuedLogs();
        } finally {
            sendLock.unlock();
        }
    }

    // The scheduler thread is shared (it also refreshes the job heartbeat), so it skips a round
    // instead of queueing behind a flush that is already sending these lines.
    @Scheduled(fixedDelay = 5000)  // Send logs every 5 seconds
    public void sendBatchedLogs() {
        if (!sendLock.tryLock()) {
            return;
        }
        try {
            sendQueuedLogs();
        } finally {
            sendLock.unlock();
        }
    }

    private void sendQueuedLogs() {
        List<Log> batch = new ArrayList<>();
        logQueue.drainTo(batch);

        if (batch.isEmpty()) {
            return;
        }

        log.info("Sending logs to Terrakube API");
        LogsRequest logsRequest = new LogsRequest();
        logsRequest.setData(batch);

        try {
            terrakubeClient.appendLogs(logsRequest);
        } catch (Exception sendLogsException) {
            log.error("Failed to send logs", sendLogsException);
            requeueLogs(batch);
        }
    }


    private void requeueLogs(List<Log> failedBatch) {
        for (int i = failedBatch.size() - 1; i >= 0; i--) {
            try {
                logQueue.putFirst(failedBatch.get(i));
            } catch (InterruptedException e) {
                log.error("Thread interrupted while re-queuing logs", e);
                Thread.currentThread().interrupt();
            }
        }
    }
}