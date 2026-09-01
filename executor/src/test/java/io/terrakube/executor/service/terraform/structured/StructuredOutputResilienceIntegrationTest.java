package io.terrakube.executor.service.terraform.structured;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.configuration.StructuredOutputProperties;
import io.terrakube.executor.service.terraform.JobContextService;
import io.terrakube.terraform.TerraformClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.terrakube.executor.service.terraform.PlanStructuredOutputService;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end guard for spec acceptance criterion 2: a context store that is slower than any
 * terraform-client stream-drain budget must never block or fail the -json line consumer.
 */
class StructuredOutputResilienceIntegrationTest {

    @Test
    void aGlacialContextStoreNeverBlocksTheLineConsumerAndNeverThrows() throws Exception {
        StructuredSnapshotPersister glacial = snapshot -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return true;
        };

        StructuredOutputProperties properties = new StructuredOutputProperties();
        properties.setQueueCapacity(128);
        StructuredOutputPersistenceQueue queue =
                new StructuredOutputPersistenceQueue(properties, new SimpleMeterRegistry(), glacial);
        queue.start();

        ExecutorFlagsProperties flags = new ExecutorFlagsProperties();
        flags.setAsyncStructuredOutput(true);
        // A FailUnkownMethod-style strict mock: any synchronous HTTP call here fails the test.
        JobContextService jobContextService = Mockito.mock(JobContextService.class, invocation -> {
            throw new AssertionError("line consumer made a synchronous context call: " + invocation.getMethod());
        });
        PlanStructuredOutputService planService = new PlanStructuredOutputService(
                jobContextService, new ObjectMapper(), Mockito.mock(TerraformClient.class), queue, flags);

        long startNanos = System.nanoTime();
        for (int i = 0; i < 500; i++) {
            planService.publishFinalPlanSnapshot("o", "1", "step-1",
                    List.of(Map.of("address", "resource_" + i)), List.of());
        }
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        assertTrue(elapsedMs < 1_000, "500 progress publications blocked for " + elapsedMs + "ms");
        assertTrue(queue.queueDepth() <= 1, "same-key snapshots should coalesce, depth=" + queue.queueDepth());

        queue.stop();
    }
}
