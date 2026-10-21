package io.terrakube.executor.service.logs;

import io.terrakube.client.TerrakubeClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
        LogsServiceApi service = new LogsServiceApi(terrakubeClient, new ExecutorLogsProperties(), new SimpleMeterRegistry());
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

    @Test
    void aLineLongerThanTheLimitIsCutInTheBatchSentToTheApi() {
        TerrakubeClient terrakubeClient = Mockito.mock(TerrakubeClient.class);
        ExecutorLogsProperties properties = new ExecutorLogsProperties();
        properties.setLiveLineMaxBytes(100);
        LogsServiceApi service = new LogsServiceApi(terrakubeClient, properties, new SimpleMeterRegistry());

        service.sendLogs(42, "1", 1, "z".repeat(150));
        service.flush();

        org.mockito.ArgumentCaptor<LogsRequest> sent = org.mockito.ArgumentCaptor.forClass(LogsRequest.class);
        Mockito.verify(terrakubeClient).appendLogs(sent.capture());
        String output = sent.getValue().getData().get(0).getOutput();
        assertTrue(output.startsWith("z".repeat(100) + " ... [50 bytes left out"));
    }

    @Test
    void theServiceLogEchoIsOnUnlessConfiguredOff() {
        ExecutorLogsProperties properties = new ExecutorLogsProperties();
        TerrakubeClient terrakubeClient = Mockito.mock(TerrakubeClient.class);
        assertTrue(new LogsServiceApi(terrakubeClient, properties, new SimpleMeterRegistry()).echoToServiceLog());

        properties.setEchoToServiceLog(false);
        assertFalse(new LogsServiceApi(terrakubeClient, properties, new SimpleMeterRegistry()).echoToServiceLog());
    }

    // While the API cannot take lines the queue must not grow with the length of the outage.
    @Test
    void theQueueStaysWithinItsByteBudgetWhileTheApiIsDownAndDropsTheOldestLines() throws Exception {
        TerrakubeClient terrakubeClient = Mockito.mock(TerrakubeClient.class);
        ExecutorLogsProperties properties = new ExecutorLogsProperties();
        properties.setApiQueueMaxBytes(2_000);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        LogsServiceApi service = new LogsServiceApi(terrakubeClient, properties, meters);
        doAnswer(invocation -> {
            throw new RuntimeException("api down");
        }).when(terrakubeClient).appendLogs(any());

        for (int line = 1; line <= 1_000; line++) {
            service.sendLogs(42, "1", line, "line " + line + " " + "x".repeat(40));
            if (line % 100 == 0) {
                service.flush(); // fails, and the lines go back in the queue
            }
            assertTrue(service.queuedBytes() <= 2_000, "queue grew to " + service.queuedBytes());
        }

        assertTrue(meters.get("terrakube.executor.logs.dropped").counter().count() > 900);
        // The API comes back: what is left is the newest lines, in order.
        List<Integer> sent = new ArrayList<>();
        doAnswer(invocation -> {
            LogsRequest request = invocation.getArgument(0);
            request.getData().forEach(logLine -> sent.add(logLine.getLineNumber()));
            return null;
        }).when(terrakubeClient).appendLogs(any());
        service.flush();

        assertFalse(sent.isEmpty());
        assertEquals(1_000, sent.get(sent.size() - 1));
        for (int i = 1; i < sent.size(); i++) {
            assertEquals(sent.get(i - 1) + 1, sent.get(i));
        }
        assertEquals(0, service.queuedBytes());
    }

    @Test
    void theQueueIsSentInRequestsThatStayWithinTheBatchBudgetAndInOrder() throws Exception {
        TerrakubeClient terrakubeClient = Mockito.mock(TerrakubeClient.class);
        ExecutorLogsProperties properties = new ExecutorLogsProperties();
        properties.setApiBatchMaxBytes(1_000);
        LogsServiceApi service = new LogsServiceApi(terrakubeClient, properties, new SimpleMeterRegistry());
        List<Integer> sent = new ArrayList<>();
        List<Integer> requestSizes = new ArrayList<>();
        doAnswer(invocation -> {
            LogsRequest request = invocation.getArgument(0);
            requestSizes.add(request.getData().size());
            request.getData().forEach(logLine -> sent.add(logLine.getLineNumber()));
            return null;
        }).when(terrakubeClient).appendLogs(any());

        for (int line = 1; line <= 100; line++) {
            service.sendLogs(42, "1", line, "x".repeat(100)); // about 200 bytes each with the overhead
        }
        service.flush();

        assertEquals(java.util.stream.IntStream.rangeClosed(1, 100).boxed().toList(), sent);
        assertTrue(requestSizes.size() > 10, "one request per round of at most about 5 lines");
        assertTrue(requestSizes.stream().allMatch(size -> size <= 5));
    }

    @Test
    void aLineLargerThanTheBatchBudgetIsStillSent() throws Exception {
        TerrakubeClient terrakubeClient = Mockito.mock(TerrakubeClient.class);
        ExecutorLogsProperties properties = new ExecutorLogsProperties();
        properties.setApiBatchMaxBytes(100);
        properties.setLiveLineMaxBytes(0);
        LogsServiceApi service = new LogsServiceApi(terrakubeClient, properties, new SimpleMeterRegistry());
        List<Integer> sent = new ArrayList<>();
        doAnswer(invocation -> {
            LogsRequest request = invocation.getArgument(0);
            request.getData().forEach(logLine -> sent.add(logLine.getLineNumber()));
            return null;
        }).when(terrakubeClient).appendLogs(any());

        service.sendLogs(42, "1", 1, "y".repeat(10_000));
        service.sendLogs(42, "1", 2, "after");
        service.flush();

        assertEquals(List.of(1, 2), sent);
    }

    // A request the API will never take must not hold back the lines behind it for the rest of the step.
    @Test
    void aBatchTheApiRefusesAsTooLargeIsDroppedAndNotSentAgain() throws Exception {
        TerrakubeClient terrakubeClient = Mockito.mock(TerrakubeClient.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ExecutorLogsProperties properties = new ExecutorLogsProperties();
        properties.setApiBatchMaxBytes(300);
        LogsServiceApi service = new LogsServiceApi(terrakubeClient, properties, meters);
        feign.Request request = feign.Request.create(feign.Request.HttpMethod.POST, "http://api/logs",
                java.util.Map.of(), null, java.nio.charset.StandardCharsets.UTF_8, null);
        List<Integer> sent = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            LogsRequest body = invocation.getArgument(0);
            if (calls.getAndIncrement() == 0) {
                throw feign.FeignException.errorStatus("appendLogs",
                        feign.Response.builder().status(413).reason("Payload Too Large").request(request)
                                .headers(java.util.Map.of()).build());
            }
            body.getData().forEach(logLine -> sent.add(logLine.getLineNumber()));
            return null;
        }).when(terrakubeClient).appendLogs(any());

        for (int line = 1; line <= 6; line++) {
            service.sendLogs(42, "1", line, "z".repeat(60));
        }
        service.flush();

        assertEquals(0, service.queuedLines());
        assertTrue(meters.get("terrakube.executor.logs.dropped").counter().count() >= 1);
        assertFalse(sent.isEmpty());
        assertFalse(sent.contains(1), "the refused batch is not sent again");
    }
}
