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

@Service
@Slf4j
@AllArgsConstructor
@ConditionalOnProperty(name = "io.executor.log-via-api", havingValue = "true", matchIfMissing = false)
public class LogsServiceApi implements ProcessLogs {
    private TerrakubeClient terrakubeClient;
    private final LinkedBlockingDeque<Log> logQueue = new LinkedBlockingDeque<>();

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

    @Scheduled(fixedDelay = 5000)  // Send logs every 5 seconds
    public void sendBatchedLogs() {
        log.info("Sending logs to Terrakube API");
        if (logQueue.isEmpty()) {
            return;
        }

        List<Log> batch = new ArrayList<>();
        logQueue.drainTo(batch);

        if (batch.isEmpty()) {
            return;
        }

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