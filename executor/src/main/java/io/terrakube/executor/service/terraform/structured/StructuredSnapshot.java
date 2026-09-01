package io.terrakube.executor.service.terraform.structured;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * An immutable, deep-copied point-in-time view of one job step's structured output, handed from a
 * Terraform/OpenTofu process-output reader thread to {@link StructuredOutputPersistenceQueue}.
 *
 * <p>The reader thread must never block on or be failed by context persistence, so the only work it
 * does per flush is build one of these (a bounded CPU-only copy) and enqueue it. Everything after -
 * the GET/merge/POST to {@code /context/v1}, retries, and the SSE live update - happens on the queue
 * worker thread.
 */
@Getter
public final class StructuredSnapshot {

    public enum Phase {
        PLAN, APPLY
    }

    /** Coalescing identity: only the newest unsaved snapshot for a given key is kept. */
    public record Key(String jobId, String stepId, Phase phase) {
    }

    /** Thrown by {@link #copyOf} when the supplied changes/diagnostics cannot be serialized. */
    public static final class SnapshotSerializationException extends RuntimeException {
        public SnapshotSerializationException(Throwable cause) {
            super(cause);
        }
    }

    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new TypeReference<>() {
    };

    private final String organizationId;
    private final String jobId;
    private final String stepId;
    private final Phase phase;
    private final long sequence;
    private final boolean finalSnapshot;
    private final List<Map<String, Object>> changes;
    private final List<Map<String, Object>> jobDiagnostics;

    private StructuredSnapshot(String organizationId, String jobId, String stepId, Phase phase, long sequence,
                              boolean finalSnapshot, List<Map<String, Object>> changes,
                              List<Map<String, Object>> jobDiagnostics) {
        this.organizationId = organizationId;
        this.jobId = jobId;
        this.stepId = stepId;
        this.phase = phase;
        this.sequence = sequence;
        this.finalSnapshot = finalSnapshot;
        this.changes = changes;
        this.jobDiagnostics = jobDiagnostics;
    }

    public static StructuredSnapshot copyOf(String organizationId, String jobId, String stepId, Phase phase,
                                            long sequence, boolean finalSnapshot,
                                            List<Map<String, Object>> changes,
                                            List<Map<String, Object>> jobDiagnostics,
                                            ObjectMapper objectMapper) {
        try {
            List<Map<String, Object>> safeChanges = objectMapper.convertValue(
                    changes == null ? List.of() : changes, LIST_OF_MAPS);
            List<Map<String, Object>> safeDiagnostics = objectMapper.convertValue(
                    jobDiagnostics == null ? List.of() : jobDiagnostics, LIST_OF_MAPS);
            return new StructuredSnapshot(organizationId, jobId, stepId, phase, sequence, finalSnapshot,
                    Collections.unmodifiableList(safeChanges), Collections.unmodifiableList(safeDiagnostics));
        } catch (IllegalArgumentException e) {
            throw new SnapshotSerializationException(e);
        }
    }

    public Key key() {
        return new Key(jobId, stepId, phase);
    }
}
