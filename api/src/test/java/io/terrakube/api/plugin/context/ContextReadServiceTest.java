package io.terrakube.api.plugin.context;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.api.plugin.storage.StorageTypeService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextReadServiceTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ContextReadService service(StorageTypeService storage) {
        return service(storage, new ContextProperties());
    }

    private ContextReadService service(StorageTypeService storage, ContextProperties properties) {
        return new ContextReadService(storage, new ContextStorageMetrics(registry), properties, registry);
    }

    private double counter(String name, String tagKey, String tagValue) {
        return registry.find(name).tag(tagKey, tagValue).counter() == null
                ? 0.0
                : registry.get(name).tag(tagKey, tagValue).counter().count();
    }

    private double counter(String name) {
        return registry.find(name).counter() == null ? 0.0 : registry.get(name).counter().count();
    }

    private double cacheRequests(String result) {
        return counter("terrakube.api.context.cache.requests", "result", result);
    }

    @Test
    void cacheHitSkipsTheStore() {
        StorageTypeService storage = Mockito.mock(StorageTypeService.class);
        when(storage.getContext(1)).thenReturn("{\"planStructuredOutput\":{}}");
        ContextReadService service = service(storage);

        assertEquals("{\"planStructuredOutput\":{}}", service.read(1));
        assertEquals("{\"planStructuredOutput\":{}}", service.read(1));

        verify(storage, times(1)).getContext(1);
        assertEquals(1.0, cacheRequests("hit"));
        assertEquals(1.0, cacheRequests("miss"));
        assertEquals(1.0, counter("terrakube.api.context.singleflight.completions", "outcome", "success"));
    }

    @Test
    void concurrentMissesAreCoalescedIntoOneStoreRead() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        StorageTypeService storage = Mockito.mock(StorageTypeService.class);
        when(storage.getContext(7)).thenAnswer(inv -> {
            calls.incrementAndGet();
            release.await(2, TimeUnit.SECONDS);
            return "{\"applyStructuredOutput\":{}}";
        });
        ContextReadService service = service(storage);

        int callers = 8;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        String[] results = new String[callers];
        Thread[] threads = new Thread[callers];
        for (int i = 0; i < callers; i++) {
            int idx = i;
            threads[i] = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                results[idx] = service.read(7);
            });
            threads[i].start();
        }
        ready.await();
        go.countDown();
        Thread.sleep(100);
        release.countDown();
        for (Thread t : threads) {
            t.join(3000);
        }

        assertEquals(1, calls.get());
        for (String r : results) {
            assertEquals("{\"applyStructuredOutput\":{}}", r);
        }
        assertEquals(1.0, cacheRequests("miss"));
        assertTrue(cacheRequests("coalesced") >= callers - 1);
    }

    @Test
    void invalidateMakesTheNextReadReturnTheFreshValueWithoutAStoreCall() {
        StorageTypeService storage = Mockito.mock(StorageTypeService.class);
        ContextReadService service = service(storage);

        service.invalidate(3, "{\"terraformOutputs\":{}}");

        assertEquals("{\"terraformOutputs\":{}}", service.read(3));
        verify(storage, Mockito.never()).getContext(anyInt());
        assertEquals(1.0, cacheRequests("hit"));
    }

    @Test
    void ownerTimeoutLeavesTheInFlightReadForLaterCallersToCoalesceOnto() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        StorageTypeService storage = Mockito.mock(StorageTypeService.class);
        when(storage.getContext(5)).thenAnswer(inv -> {
            calls.incrementAndGet();
            release.await(2, TimeUnit.SECONDS);
            return "{\"planStructuredOutput\":{}}";
        });
        ContextProperties properties = new ContextProperties();
        properties.setReadTimeout(Duration.ofMillis(100));
        ContextReadService service = service(storage, properties);

        // Owner starts the read and times out at 100ms while the store call is still blocked.
        assertThrows(ContextUnavailableException.class, () -> service.read(5));
        assertEquals(1.0, counter("terrakube.api.context.read.waiter.timeouts"));

        // A second caller arrives before the store call finishes - it must coalesce, not start a
        // duplicate read (and it also times out on its own budget).
        assertThrows(ContextUnavailableException.class, () -> service.read(5));

        // Let the single backing read finish; its result must land in the cache.
        release.countDown();
        Thread.sleep(300);

        assertEquals("{\"planStructuredOutput\":{}}", service.read(5));
        assertEquals(1, calls.get(), "exactly one object-store read despite the owner timing out");
        assertEquals(1.0, counter("terrakube.api.context.singleflight.completions", "outcome", "success"));
    }

    @Test
    void storageFailureIsNotNegativeCached() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StorageTypeService storage = Mockito.mock(StorageTypeService.class);
        when(storage.getContext(6)).thenAnswer(inv -> {
            if (calls.getAndIncrement() == 0) {
                throw new RuntimeException("S3 503");
            }
            return "{\"planStructuredOutput\":{}}";
        });
        ContextReadService service = service(storage);

        assertThrows(ContextUnavailableException.class, () -> service.read(6));
        Thread.sleep(50);
        assertEquals(1.0, counter("terrakube.api.context.singleflight.completions", "outcome", "failure"));

        // A recovered object must be visible on the next request.
        assertEquals("{\"planStructuredOutput\":{}}", service.read(6));
        assertEquals(2, calls.get());
    }

    @Test
    void missingObjectReturnsNullAndIsNotCached() {
        StorageTypeService storage = Mockito.mock(StorageTypeService.class);
        when(storage.getContext(9)).thenReturn(null);
        ContextReadService service = service(storage);

        assertNull(service.read(9));
        assertNull(service.read(9));
        verify(storage, times(2)).getContext(9);
    }

    @Test
    void workerPoolSaturationReturnsControlledUnavailable() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        StorageTypeService storage = Mockito.mock(StorageTypeService.class);
        when(storage.getContext(anyInt())).thenAnswer(inv -> {
            release.await(3, TimeUnit.SECONDS);
            return "{}";
        });
        ContextProperties properties = new ContextProperties();
        properties.setReadWorkers(1);
        properties.setReadQueueCapacity(1);
        properties.setReadTimeout(Duration.ofMillis(200));
        ContextReadService service = service(storage, properties);

        // Fill the one worker + one queue slot with distinct jobs, then a third distinct job is rejected.
        Thread a = new Thread(() -> { try { service.read(100); } catch (RuntimeException ignored) { } });
        Thread b = new Thread(() -> { try { service.read(101); } catch (RuntimeException ignored) { } });
        a.start();
        b.start();
        Thread.sleep(100);

        assertThrows(ContextUnavailableException.class, () -> service.read(102));
        assertTrue(cacheRequests("saturation") >= 1.0);

        release.countDown();
        a.join(2000);
        b.join(2000);
    }
}
