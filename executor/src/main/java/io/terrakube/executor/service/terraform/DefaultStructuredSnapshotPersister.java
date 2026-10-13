package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.service.logs.ProcessLogs;
import io.terrakube.executor.service.terraform.structured.StructuredSnapshot;
import io.terrakube.executor.service.terraform.structured.StructuredSnapshotPersister;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Runs entirely on the {@code structured-output-persistence} worker thread: patches this step's
 * plan/apply changes + diagnostics into the job context (no read first), and - only when the patch
 * was applied - emits the best-effort SSE live update. Any failure returns {@code false} so the
 * queue can retry; nothing here can reach a Terraform reader thread.
 */
@Slf4j
@Service
public class DefaultStructuredSnapshotPersister implements StructuredSnapshotPersister {

    private final JobContextService jobContextService;
    private final PlanStructuredOutputService planStructuredOutputService;
    private final ApplyStructuredOutputService applyStructuredOutputService;
    private final ProcessLogs processLogs;
    private final ObjectMapper objectMapper;

    public DefaultStructuredSnapshotPersister(JobContextService jobContextService,
                                              PlanStructuredOutputService planStructuredOutputService,
                                              ApplyStructuredOutputService applyStructuredOutputService,
                                              ProcessLogs processLogs,
                                              ObjectMapper objectMapper) {
        this.jobContextService = jobContextService;
        this.planStructuredOutputService = planStructuredOutputService;
        this.applyStructuredOutputService = applyStructuredOutputService;
        this.processLogs = processLogs;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean persist(StructuredSnapshot snapshot) {
        String organizationId = snapshot.getOrganizationId();
        String jobId = snapshot.getJobId();
        String stepId = snapshot.getStepId();
        boolean planPhase = snapshot.getPhase() == StructuredSnapshot.Phase.PLAN;
        try {
            Map<String, Object> set = planPhase
                    ? planStructuredOutputService.buildPlanPatch(stepId, snapshot.getChanges(), snapshot.getJobDiagnostics(), snapshot.isFinalSnapshot())
                    : applyStructuredOutputService.buildApplyPatch(stepId, snapshot.getChanges(), snapshot.getJobDiagnostics());
            // Machine-readable persistence status the API/UI can reason about without reading any
            // Terraform payload content. Carries no variable values or secrets.
            set.put("structuredOutputStatus", Map.of(
                    "state", "PERSISTED",
                    "updatedAtEpochMs", snapshot.getCreatedAtEpochMs(),
                    "stepId", stepId,
                    "phase", planPhase ? "plan" : "apply",
                    "finalSnapshot", snapshot.isFinalSnapshot()));

            // The sequence is the revision, so a retried or recovered snapshot that something newer
            // already superseded comes back SKIPPED: done, but nothing new to push live.
            JobContextService.PatchResult result =
                    jobContextService.patchContext(organizationId, jobId, snapshot.getSequence(), set);
            if (result == JobContextService.PatchResult.APPLIED) {
                pushLiveUpdate(planPhase ? "plan" : "apply", jobId, stepId, snapshot);
            }
            return result != JobContextService.PatchResult.FAILED;
        } catch (Throwable t) {
            log.warn("Unable to persist structured output for job {} step {} phase {}: {}",
                    jobId, stepId, planPhase ? "plan" : "apply", t.toString());
            return false;
        }
    }

    private void pushLiveUpdate(String phase, String jobId, String stepId, StructuredSnapshot snapshot) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("phase", phase);
            payload.put("changes", Map.of(stepId, snapshot.getChanges()));
            payload.put("jobDiagnostics", Map.of(stepId, snapshot.getJobDiagnostics()));
            processLogs.sendStructuredUpdate(Integer.valueOf(jobId), stepId, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            // Best-effort: an SSE/serialization failure must not turn a successful context write
            // into a retried (and eventually failed) persist.
            log.warn("Unable to push live structured update for job {} step {}: {}", jobId, stepId, e.getMessage());
        }
    }
}
