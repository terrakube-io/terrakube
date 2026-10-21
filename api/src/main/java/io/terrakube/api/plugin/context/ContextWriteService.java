package io.terrakube.api.plugin.context;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The read-apply-write behind {@code /context/v1} writes. It runs under the job's
 * {@link ContextWriteLock}, so writers on different API pods are serialized and each one merges
 * into the latest stored document. No database transaction is open while storage is read and
 * written. When the stored document cannot be read, nothing is written; a malformed one blocks
 * patches but is repaired by a full replace.
 */
@Slf4j
@Service
@AllArgsConstructor
public class ContextWriteService {
    private static final Set<JobStatus> CONTEXT_WRITABLE_JOB_STATUSES = EnumSet.of(
            JobStatus.pending,
            JobStatus.waitingApproval,
            JobStatus.approved,
            JobStatus.queue,
            JobStatus.running,
            JobStatus.completed,
            JobStatus.noChanges);

    private static final String POLICY_KEY = "policyEvaluation";

    /** {@code context} is the saved document, {@code null} when every entry was stale. */
    public record PatchResult(List<String> applied, int skipped, String context) {
    }

    public record ReplaceResult(String saved, boolean policyChanged) {
    }

    private final JobRepository jobRepository;

    private final ContextWriteLock contextWriteLock;

    private final ContextReadService contextReadService;

    private final ContextSanitizer contextSanitizer;

    private final StorageTypeService storageTypeService;

    private final ContextStorageMetrics contextStorageMetrics;

    private final ObjectMapper objectMapper;

    /** Applies a {@link ContextPatchApplier#parse validated} patch. */
    public PatchResult patch(int jobId, ContextPatchApplier.Patch patch) throws IOException {
        String token = contextWriteLock.acquire(jobId);
        try {
            checkWritable(jobId);
            ObjectNode stored = readStored(jobId);
            if (stored == null) {
                throw new ContextUnavailableException("stored context for job " + jobId + " is malformed", null);
            }
            List<String> applied = ContextPatchApplier.apply(stored, patch);
            int skipped = patch.set().size() - applied.size();
            if (applied.isEmpty()) {
                return new PatchResult(applied, skipped, null);
            }
            return new PatchResult(applied, skipped, save(jobId, stored));
        } finally {
            contextWriteLock.release(jobId, token);
        }
    }

    /**
     * Applies a recovery patch from spooled storage under the job's {@link ContextWriteLock}.
     * Unlike {@link #patch}, this does not require the job to be in an active writable status
     * (e.g. dead-executor recovery sweeps run on failed jobs), and repairs malformed context
     * rather than rejecting the patch.
     */
    public PatchResult patchRecovery(int jobId, long revision, Map<String, Object> set) throws IOException {
        long effectiveRevision = revision > 0 ? revision : 1L;
        ObjectNode body = objectMapper.createObjectNode();
        body.put("revision", effectiveRevision);
        body.set("set", objectMapper.valueToTree(set));
        ContextPatchApplier.Patch patch = ContextPatchApplier.parse(body, System.currentTimeMillis());

        String token = contextWriteLock.acquire(jobId);
        try {
            ObjectNode stored = readStored(jobId);
            if (stored == null) {
                log.warn("Repairing malformed stored context for job {}", jobId);
                stored = objectMapper.createObjectNode();
            }
            List<String> applied = ContextPatchApplier.apply(stored, patch);
            int skipped = patch.set().size() - applied.size();
            if (applied.isEmpty()) {
                return new PatchResult(applied, skipped, null);
            }
            return new PatchResult(applied, skipped, save(jobId, stored));
        } finally {
            contextWriteLock.release(jobId, token);
        }
    }

    /** Replaces the whole document, except revisioned entries, which only change through a patch. */
    public ReplaceResult replace(int jobId, ObjectNode incoming) throws IOException {
        String token = contextWriteLock.acquire(jobId);
        try {
            checkWritable(jobId);
            ObjectNode stored = readStored(jobId);
            if (stored == null) {
                // Nothing in it can be protected, and this write repairs it.
                log.warn("Replacing malformed stored context for job {}", jobId);
                stored = objectMapper.createObjectNode();
            }
            ContextPatchApplier.protectRevisions(stored, incoming);
            boolean policyChanged = !Objects.equals(stored.get(POLICY_KEY), incoming.get(POLICY_KEY));
            return new ReplaceResult(save(jobId, incoming), policyChanged);
        } finally {
            contextWriteLock.release(jobId, token);
        }
    }

    // No row lock, so this check is not serialized with status changes: a write can land just after
    // the job moved to a terminal status. That is harmless, completed and noChanges are writable
    // anyway and a late snapshot on a failed job hurts nothing.
    private void checkWritable(int jobId) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> {
            log.warn("Cannot save context for missing job {}", jobId);
            return new ResponseStatusException(HttpStatus.NOT_FOUND);
        });
        if (!CONTEXT_WRITABLE_JOB_STATUSES.contains(job.getStatus())) {
            log.warn("Cannot save context for job {} with status {}", jobId, job.getStatus());
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
    }

    /**
     * @return the stored document, empty when none exists yet, {@code null} when it is malformed.
     * @throws ContextUnavailableException when it cannot be read.
     */
    private ObjectNode readStored(int jobId) {
        String context = contextReadService.readFresh(jobId);
        if (context == null || context.isBlank()) {
            return objectMapper.createObjectNode();
        }
        JsonNode stored;
        try {
            stored = objectMapper.readTree(context);
        } catch (JacksonException e) {
            return null;
        }
        return stored instanceof ObjectNode object ? object : null;
    }

    private String save(int jobId, JsonNode document) throws IOException {
        String sanitized = contextSanitizer.sanitize(objectMapper.writeValueAsString(document));
        String saved = contextStorageMetrics.time("write", () -> storageTypeService.saveContext(jobId, sanitized));
        // Refresh the read cache so a Job Details page open right after the write sees this snapshot.
        contextReadService.invalidate(jobId, saved);
        return saved;
    }
}
