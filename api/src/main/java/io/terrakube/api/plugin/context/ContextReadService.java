package io.terrakube.api.plugin.context;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.terrakube.api.plugin.storage.StorageTypeService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fast, coalesced {@code /context/v1} reads. A short per-pod Caffeine cache absorbs repeat opens of
 * the same Job Details page; concurrent cache misses for one job are collapsed into a single
 * object-store request (single-flight) so an intermittent S3 delay is not amplified by request
 * fan-out. Failures ({@code 503}, timeout, malformed) are never negative-cached.
 */
@Slf4j
@Service
public class ContextReadService {

    private final StorageTypeService storageTypeService;
    private final ContextStorageMetrics contextStorageMetrics;
    private final ContextProperties properties;
    private final MeterRegistry meterRegistry;

    private final Cache<Integer, String> cache;
    private final ConcurrentHashMap<Integer, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor ioPool;

    public ContextReadService(StorageTypeService storageTypeService,
                              ContextStorageMetrics contextStorageMetrics,
                              ContextProperties properties,
                              MeterRegistry meterRegistry) {
        this.storageTypeService = storageTypeService;
        this.contextStorageMetrics = contextStorageMetrics;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.cache = Caffeine.newBuilder()
                .maximumSize(Math.max(1, properties.getCacheMaxEntries()))
                .expireAfterWrite(properties.getCacheTtl())
                .build();
        AtomicInteger threadIndex = new AtomicInteger();
        int workers = Math.max(1, properties.getReadWorkers());
        this.ioPool = new ThreadPoolExecutor(
                Math.min(2, workers), workers, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, properties.getReadQueueCapacity())),
                r -> {
                    Thread t = new Thread(r, "context-read-" + threadIndex.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        Gauge.builder("terrakube.api.context.inflight.reads", inFlight, Map::size)
                .description("In-flight object-store context reads (single-flight)")
                .register(meterRegistry);
    }

    @PreDestroy
    void shutdown() {
        ioPool.shutdownNow();
    }

    /**
     * @return the raw stored context JSON, {@code null} when the object does not exist yet.
     * @throws ContextUnavailableException on timeout, storage failure, or worker saturation
     *         (never negative-cached).
     */
    public String read(int jobId) {
        String cached = cache.getIfPresent(jobId);
        if (cached != null) {
            countRequest("hit");
            return cached;
        }

        boolean[] owner = {false};
        CompletableFuture<String> future = inFlight.computeIfAbsent(jobId, id -> {
            owner[0] = true;
            return startLoad(id);
        });
        if (owner[0]) {
            // Lifecycle is tied to the backing object-store operation, NOT to any waiting caller:
            // the in-flight entry is removed only when this future completes.
            future.whenComplete((value, error) -> onLoadComplete(jobId, future, value, error));
        }
        countRequest(owner[0] ? "miss" : "coalesced");

        try {
            String result = future.get(properties.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (result != null && !result.isBlank()) {
                cache.put(jobId, result);
            }
            return result;
        } catch (TimeoutException e) {
            // This caller's budget expired; the backing read keeps running and later callers still
            // coalesce onto it. Do NOT remove the in-flight entry here.
            meterRegistry.counter("terrakube.api.context.read.waiter.timeouts").increment();
            countRequest("failure");
            throw new ContextUnavailableException("context read timed out for job " + jobId, e);
        } catch (ExecutionException e) {
            countRequest("failure");
            throw new ContextUnavailableException("context read failed for job " + jobId, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            countRequest("failure");
            throw new ContextUnavailableException("context read interrupted for job " + jobId, e);
        }
    }

    private CompletableFuture<String> startLoad(int jobId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            ioPool.execute(() -> {
                try {
                    future.complete(loadFromStore(jobId));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException saturated) {
            countRequest("saturation");
            log.warn("Context read worker pool saturated for job {}", jobId);
            future.completeExceptionally(saturated);
        }
        return future;
    }

    private void onLoadComplete(int jobId, CompletableFuture<String> future, String value, Throwable error) {
        inFlight.remove(jobId, future);
        if (error == null) {
            if (value != null && !value.isBlank()) {
                cache.put(jobId, value);
            }
            meterRegistry.counter("terrakube.api.context.singleflight.completions", "outcome", "success").increment();
        } else {
            // Never negative-cache: nothing is stored for a failed/malformed read.
            meterRegistry.counter("terrakube.api.context.singleflight.completions", "outcome", "failure").increment();
        }
    }

    /** Refresh (or clear) the cached context after a successful write, so the next read sees it. */
    public void invalidate(int jobId, String freshContext) {
        if (freshContext == null || freshContext.isBlank()) {
            cache.invalidate(jobId);
        } else {
            cache.put(jobId, freshContext);
        }
    }

    private String loadFromStore(int jobId) {
        try {
            return contextStorageMetrics.time("read", () -> storageTypeService.getContext(jobId));
        } catch (IOException | RuntimeException e) {
            throw new ContextUnavailableException("context storage read failed for job " + jobId, e);
        }
    }

    private void countRequest(String result) {
        meterRegistry.counter("terrakube.api.context.cache.requests", "result", result).increment();
    }
}
