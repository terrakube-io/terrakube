package io.terrakube.api.plugin.scheduler;

/**
 * Hands the running worker's {@link ExecutionLease} to the code that submits to the executor, which
 * runs on the same thread but several calls below the scheduler. Empty outside a scheduled run.
 */
public final class ExecutionLeaseContext {
    private static final ThreadLocal<ExecutionLease> CURRENT = new ThreadLocal<>();

    private ExecutionLeaseContext() {
    }

    public static ExecutionLease current() {
        return CURRENT.get();
    }

    public static void bind(ExecutionLease lease) {
        CURRENT.set(lease);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
