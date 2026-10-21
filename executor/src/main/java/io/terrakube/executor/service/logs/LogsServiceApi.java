package io.terrakube.executor.service.logs;

import feign.FeignException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.job.Log;
import io.terrakube.client.model.organization.job.LogsRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Service
@Slf4j
@ConditionalOnProperty(name = "io.executor.log-via-api", havingValue = "true", matchIfMissing = false)
public class LogsServiceApi implements ProcessLogs {
    // What a line costs in the queue besides its text: the object, the ids and the line number.
    private static final int LINE_OVERHEAD_BYTES = 96;
    private static final long DROP_WARNING_INTERVAL_MILLIS = 10_000L;

    private final TerrakubeClient terrakubeClient;
    private final ExecutorLogsProperties properties;
    private final Counter dropped;
    // A line with its size, worked out once.
    private record Queued(Log line, long bytes) {
    }

    private final LinkedBlockingDeque<Queued> logQueue = new LinkedBlockingDeque<>();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicLong lastDropWarning = new AtomicLong();
    // Held while a batch is in flight, so flush() can't return while a scheduled batch it never
    // saw is still being sent.
    private final ReentrantLock sendLock = new ReentrantLock();

    public LogsServiceApi(TerrakubeClient terrakubeClient, ExecutorLogsProperties properties, MeterRegistry meterRegistry) {
        this.terrakubeClient = terrakubeClient;
        this.properties = properties;
        this.dropped = Counter.builder("terrakube.executor.logs.dropped")
                .description("Output lines left out of the live view because the API could not take them in time")
                .tag("transport", "api")
                .register(meterRegistry);
    }

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
        logEntry.setOutput(LiveLogLine.cap(output, properties.getLiveLineMaxBytes()));

        long bytes = sizeOf(logEntry);
        logQueue.addLast(new Queued(logEntry, bytes));
        queuedBytes.addAndGet(bytes);
        dropOldestBeyondTheBudget();
    }

    @Override
    public boolean echoToServiceLog() {
        return properties.isEchoToServiceLog();
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

    // Sends the queue in requests of at most apiBatchMaxBytes, and stops at the first one the API does
    // not take: what is left stays queued, within its byte budget, for the next round.
    private void sendQueuedLogs() {
        while (true) {
            List<Queued> batch = takeBatch();
            if (batch.isEmpty()) {
                return;
            }

            LogsRequest logsRequest = new LogsRequest();
            logsRequest.setData(batch.stream().map(Queued::line).toList());
            try {
                terrakubeClient.appendLogs(logsRequest);
            } catch (Exception sendLogsException) {
                if (sendLogsException instanceof FeignException feign && feign.status() == 413) {
                    // The API will never take this request. Sending it again would only block the lines
                    // behind it, so it is dropped from the live view like any other overflow.
                    log.error("The API refused a batch of {} log lines as too large, it is dropped from the live view", batch.size());
                    dropped.increment(batch.size());
                    continue;
                }
                log.error("Failed to send logs", sendLogsException);
                requeueLogs(batch);
                return;
            }
        }
    }

    private List<Queued> takeBatch() {
        List<Queued> batch = new ArrayList<>();
        long bytes = 0;
        long maxBytes = Math.max(1, properties.getApiBatchMaxBytes());
        Queued next;
        while ((next = logQueue.peekFirst()) != null) {
            // A batch always takes at least one line, however large, so a line can never get stuck.
            if (!batch.isEmpty() && bytes + next.bytes() > maxBytes) {
                break;
            }
            Queued taken = logQueue.pollFirst();
            if (taken == null) {
                break;
            }
            queuedBytes.addAndGet(-taken.bytes());
            batch.add(taken);
            bytes += taken.bytes();
        }
        return batch;
    }

    private void requeueLogs(List<Queued> failedBatch) {
        for (int i = failedBatch.size() - 1; i >= 0; i--) {
            Queued line = failedBatch.get(i);
            logQueue.addFirst(line);
            queuedBytes.addAndGet(line.bytes());
        }
        dropOldestBeyondTheBudget();
    }

    // The oldest lines go first: a viewer follows the end of the output, and the saved output is not
    // built from this queue, so nothing that is dropped here is lost for good.
    private void dropOldestBeyondTheBudget() {
        long maxBytes = properties.getApiQueueMaxBytes();
        if (maxBytes <= 0) {
            return;
        }
        long droppedNow = 0;
        while (queuedBytes.get() > maxBytes) {
            Queued oldest = logQueue.pollFirst();
            if (oldest == null) {
                break;
            }
            queuedBytes.addAndGet(-oldest.bytes());
            droppedNow++;
        }
        if (droppedNow > 0) {
            dropped.increment(droppedNow);
            long now = System.currentTimeMillis();
            long last = lastDropWarning.get();
            if (now - last >= DROP_WARNING_INTERVAL_MILLIS && lastDropWarning.compareAndSet(last, now)) {
                log.warn("The API did not take log lines fast enough: the oldest lines were left out of the live view to stay within {} bytes. The saved output has them all.", maxBytes);
            }
        }
    }

    private static long sizeOf(Log line) {
        String output = line.getOutput();
        return LINE_OVERHEAD_BYTES + (output == null ? 0 : output.getBytes(StandardCharsets.UTF_8).length);
    }

    // For tests.
    long queuedBytes() {
        return queuedBytes.get();
    }

    int queuedLines() {
        return logQueue.size();
    }
}
