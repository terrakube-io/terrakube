package io.terrakube.executor.service.terraform.structured;

/**
 * Persists one coalesced {@link StructuredSnapshot} to the job context store. Called only from the
 * {@link StructuredOutputPersistenceQueue} worker thread - never from a Terraform reader thread.
 *
 * <p>Implementations must never throw: a failure is reported by returning {@code false}, which lets
 * the queue apply retry/backoff and, ultimately, a metric-and-warn without touching the Terraform
 * result.
 */
public interface StructuredSnapshotPersister {

    /** @return {@code true} only when the context write succeeded. */
    boolean persist(StructuredSnapshot snapshot);
}
