package io.terrakube.executor.service.executor;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-pod "one accepted job at a time" gate. Acquired synchronously by OnlineModeServiceImpl
 * before a job is dispatched (so a second concurrent request sees a 503 immediately instead of
 * being queued locally); released by ExecutorJobImpl once that job finishes, in its finally
 * block, on every path (success, prep failure, unexpected exception).
 */
@Component
public class ExecutorCapacityGate {

    private final AtomicBoolean busy = new AtomicBoolean(false);

    public boolean tryAcquire() {
        return busy.compareAndSet(false, true);
    }

    public void release() {
        busy.set(false);
    }
}
