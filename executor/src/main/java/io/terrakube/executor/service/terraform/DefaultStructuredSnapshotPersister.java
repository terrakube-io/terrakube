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
 * Runs entirely on the {@code structured-output-persistence} worker thread: reads the current job
 * context, merges this step's plan/apply changes + diagnostics, POSTs it back, and - only on a
 * successful write - emits the best-effort SSE live update. Any failure returns {@code false} so the
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
            Map<String, Object> context = jobContextService.getCurrentContext(organizationId, jobId);
            Map<String, Object> updated = planPhase
                    ? planStructuredOutputService.updateContext(context, stepId, snapshot.getChanges(), snapshot.getJobDiagnostics())
                    : applyStructuredOutputService.updateApplyContext(context, stepId, snapshot.getChanges(), snapshot.getJobDiagnostics());

            boolean saved = jobContextService.saveContextChecked(organizationId, jobId, updated);
            if (saved) {
                pushLiveUpdate(planPhase ? "plan" : "apply", jobId, stepId, snapshot);
            }
            return saved;
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
