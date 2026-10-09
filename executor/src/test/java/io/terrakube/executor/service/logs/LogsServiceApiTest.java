package io.terrakube.executor.service.logs;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.job.LogsRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

class LogsServiceApiTest {

    // A step reports itself finished right after flush() returns, so flush must not come back
    // while any line is still unsent - including a batch the scheduler already took off the queue.
    @Test
    void flushReturnsOnlyOnceEveryQueuedLineHasBeenSent() throws Exception {
        TerrakubeClient terrakubeClient = Mockito.mock(TerrakubeClient.class);
        LogsServiceApi service = new LogsServiceApi(terrakubeClient);
        List<Integer> sentLines = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch scheduledBatchInFlight = new CountDownLatch(1);
        CountDownLatch releaseScheduledBatch = new CountDownLatch(1);
        doAnswer(invocation -> {
            LogsRequest request = invocation.getArgument(0);
            if (calls.getAndIncrement() == 0) {
                scheduledBatchInFlight.countDown();
                releaseScheduledBatch.await(5, TimeUnit.SECONDS);
            }
            request.getData().forEach(line -> sentLines.add(line.getLineNumber()));
            return null;
        }).when(terrakubeClient).appendLogs(any());

        service.sendLogs(42, "1", 1, "first");
        CompletableFuture<Void> scheduledRun = CompletableFuture.runAsync(service::sendBatchedLogs);
        assertTrue(scheduledBatchInFlight.await(5, TimeUnit.SECONDS));
        service.sendLogs(42, "1", 2, "second");

        CompletableFuture<Void> flush = CompletableFuture.runAsync(service::flush);
        Thread.sleep(200);
        assertFalse(flush.isDone(), "flush returned while line 1 was still in flight");

        releaseScheduledBatch.countDown();
        flush.get(5, TimeUnit.SECONDS);
        scheduledRun.get(5, TimeUnit.SECONDS);
        assertEquals(List.of(1, 2), sentLines);
    }
}
