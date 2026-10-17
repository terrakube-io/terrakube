package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.service.terraform.spool.ContextSpoolService;
import io.terrakube.executor.service.terraform.structured.StructuredOutputPersistenceQueue;
import io.terrakube.executor.service.terraform.structured.StructuredSnapshot;
import io.terrakube.terraform.TerraformClient;
import io.terrakube.terraform.TerraformProcessData;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.TextStringBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import io.terrakube.executor.service.mode.TerraformJob;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

@Slf4j
@Service
public class PlanStructuredOutputService {

    private static final String CONTEXT_PLAN_KEY = "planStructuredOutput";
    private static final String CONTEXT_UI_KEY = "terrakubeUI";
    private static final String CONTEXT_JOB_DIAGNOSTICS_KEY = "jobDiagnostics";
    static final String CONTEXT_NO_CHANGE_PLAN_KEY = "noChangePlan";
    static final String CONTEXT_PLAN_RENDERED_TEXT_KEY = "planRenderedText";
    private static final String STRUCTURED_PLAN_MARKER = "<div data-terrakube-structured-plan=\"true\"></div>";

    private final JobContextService jobContextService;
    private final ObjectMapper objectMapper;
    TerraformClient terraformClient;
    private final StructuredOutputPersistenceQueue persistenceQueue;
    private final ExecutorFlagsProperties executorFlagsProperties;
    private final ContextSpoolService contextSpoolService;
    private final io.terrakube.executor.service.logs.ProcessLogs processLogs;

    @Autowired
    public PlanStructuredOutputService(
            JobContextService jobContextService,
            ObjectMapper objectMapper,
            TerraformClient terraformClient,
            StructuredOutputPersistenceQueue persistenceQueue,
            ExecutorFlagsProperties executorFlagsProperties,
            @Autowired(required = false) ContextSpoolService contextSpoolService,
            @Autowired(required = false) io.terrakube.executor.service.logs.ProcessLogs processLogs) {
        this.jobContextService = jobContextService;
        this.objectMapper = objectMapper;
        this.terraformClient = terraformClient;
        this.persistenceQueue = persistenceQueue;
        this.executorFlagsProperties = executorFlagsProperties;
        this.contextSpoolService = contextSpoolService;
        this.processLogs = processLogs;
    }

    public PlanStructuredOutputService(
            JobContextService jobContextService,
            ObjectMapper objectMapper,
            TerraformClient terraformClient,
            StructuredOutputPersistenceQueue persistenceQueue,
            ExecutorFlagsProperties executorFlagsProperties,
            ContextSpoolService contextSpoolService) {
        this(jobContextService, objectMapper, terraformClient, persistenceQueue, executorFlagsProperties, contextSpoolService, null);
    }

    public PlanStructuredOutputService(
            JobContextService jobContextService,
            ObjectMapper objectMapper,
            TerraformClient terraformClient,
            StructuredOutputPersistenceQueue persistenceQueue,
            ExecutorFlagsProperties executorFlagsProperties) {
        this(jobContextService, objectMapper, terraformClient, persistenceQueue, executorFlagsProperties, null, null);
    }

    // Convenience constructor for tests and callers that keep the previous synchronous behaviour.
    public PlanStructuredOutputService(
            JobContextService jobContextService,
            ObjectMapper objectMapper,
            TerraformClient terraformClient) {
        this(jobContextService, objectMapper, terraformClient, null, synchronousFlags(), null);
    }

    private static ExecutorFlagsProperties synchronousFlags() {
        ExecutorFlagsProperties flags = new ExecutorFlagsProperties();
        flags.setAsyncStructuredOutput(false);
        return flags;
    }

    public void publishPlanSummary(TerraformJob terraformJob, File terraformWorkingDir, List<Map<String, Object>> liveChanges, List<Map<String, Object>> jobDiagnostics) {
        try {
            String planJson = getPlanAsJson(terraformJob, terraformWorkingDir);
            if (planJson == null || planJson.isBlank()) {
                return;
            }

            List<Map<String, Object>> changes = liveChanges != null && !liveChanges.isEmpty()
                    ? mergeShowJsonDiff(liveChanges, planJson)
                    : buildChangesFromPlanJson(planJson);

            if (executorFlagsProperties.isAsyncStructuredOutput() && persistenceQueue != null) {
                publishFinalPlanSnapshot(terraformJob.getOrganizationId(), terraformJob.getJobId(),
                        terraformJob.getStepId(), changes, jobDiagnostics);
                return;
            }

            publishFinalPlanSnapshotSynchronous(terraformJob.getOrganizationId(), terraformJob.getJobId(),
                    terraformJob.getStepId(), changes, jobDiagnostics);
        } catch (InterruptedException e) {
            log.error("Interrupted while publishing plan summary", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Unable to publish structured plan output for job {} step {}", terraformJob.getJobId(),
                    terraformJob.getStepId(), e);
        }
    }

    public String publishFinalPlanSynchronous(TerraformJob terraformJob, File terraformWorkingDir, List<Map<String, Object>> liveChanges, List<Map<String, Object>> jobDiagnostics) {
        long revision = (terraformJob != null && terraformJob.getDeliveryRevision() != null)
                ? terraformJob.getDeliveryRevision()
                : 1L;
        return publishFinalPlanSynchronous(terraformJob, terraformWorkingDir, revision, liveChanges, jobDiagnostics);
    }

    public String publishFinalPlanSynchronous(TerraformJob terraformJob, File terraformWorkingDir, long deliveryRevision, List<Map<String, Object>> liveChanges, List<Map<String, Object>> jobDiagnostics) {
        try {
            String planJson = getPlanAsJson(terraformJob, terraformWorkingDir);
            List<Map<String, Object>> changes;
            if (planJson != null && !planJson.isBlank()) {
                changes = liveChanges != null && !liveChanges.isEmpty()
                        ? mergeShowJsonDiff(liveChanges, planJson)
                        : buildChangesFromPlanJson(planJson);
            } else {
                changes = liveChanges != null ? liveChanges : new ArrayList<>();
            }

            return publishFinalPlanSnapshotSynchronous(terraformJob.getOrganizationId(), terraformJob.getJobId(),
                    terraformJob.getStepId(), deliveryRevision, changes, jobDiagnostics);
        } catch (InterruptedException e) {
            log.error("Interrupted while publishing plan summary", e);
            Thread.currentThread().interrupt();
            return "NOT_DURABLE";
        } catch (Exception e) {
            log.warn("Unable to publish structured plan output for job {} step {}", terraformJob.getJobId(),
                    terraformJob.getStepId(), e);
            return "NOT_DURABLE";
        }
    }

    public String publishFinalPlanSnapshotSynchronous(String organizationId, String jobId, String stepId,
                                                      List<Map<String, Object>> changes, List<Map<String, Object>> jobDiagnostics) {
        return publishFinalPlanSnapshotSynchronous(organizationId, jobId, stepId, 1L, changes, jobDiagnostics);
    }

    public String publishFinalPlanSnapshotSynchronous(String organizationId, String jobId, String stepId, long deliveryRevision,
                                                      List<Map<String, Object>> changes, List<Map<String, Object>> jobDiagnostics) {
        Map<String, Object> updatedContext = null;
        try {
            Map<String, Object> context = getCurrentContext(organizationId, jobId);
            updatedContext = updateContext(context, stepId, changes, jobDiagnostics);
            applyNoChangePlanMarker(updatedContext, stepId, changes, jobDiagnostics);
            updatedContext.put("structuredOutputStatus", Map.of(
                    "state", "PERSISTED",
                    "updatedAtEpochMs", System.currentTimeMillis(),
                    "stepId", stepId,
                    "phase", "plan",
                    "finalSnapshot", true));

            for (int attempt = 1; attempt <= 3; attempt++) {
                boolean saved = jobContextService.saveContextChecked(organizationId, jobId, updatedContext);
                if (saved) {
                    log.info("Synchronous final plan snapshot persisted and ACKed for job {} step {}", jobId, stepId);
                    pushLiveUpdate("plan", jobId, stepId, changes, jobDiagnostics);
                    return "ACKED";
                }
                if (attempt < 3) {
                    try {
                        Thread.sleep(200L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            log.warn("Failed to obtain synchronous ACK for final plan snapshot for job {} step {} after 3 attempts", jobId, stepId);
        } catch (Exception e) {
            log.warn("Error publishing synchronous final plan snapshot for job {} step {}: {}", jobId, stepId, e.getMessage());
            if (updatedContext == null) {
                updatedContext = new LinkedHashMap<>();
                updatedContext.put(CONTEXT_PLAN_KEY, Map.of(stepId, changes != null ? changes : List.of()));
                if (jobDiagnostics != null && !jobDiagnostics.isEmpty()) {
                    updatedContext.put(CONTEXT_JOB_DIAGNOSTICS_KEY, Map.of(stepId, jobDiagnostics));
                }
                updatedContext.put(CONTEXT_UI_KEY, Map.of(stepId, STRUCTURED_PLAN_MARKER));
                applyNoChangePlanMarker(updatedContext, stepId, changes, jobDiagnostics);
                updatedContext.put("structuredOutputStatus", Map.of(
                        "state", "PERSISTED",
                        "updatedAtEpochMs", System.currentTimeMillis(),
                        "stepId", stepId,
                        "phase", "plan",
                        "finalSnapshot", true));
            }
        }

        if (contextSpoolService != null && updatedContext != null) {
            String spooledPath = contextSpoolService.spoolContextPatch(organizationId, jobId, stepId, "plan", deliveryRevision, updatedContext);
            if (spooledPath == null) {
                spooledPath = contextSpoolService.spoolContextPatch(organizationId, jobId, stepId, "plan", updatedContext);
            }
            if (spooledPath != null) {
                log.info("Successfully spooled final plan snapshot to storage at {} for job {} step {}", spooledPath, jobId, stepId);
                return "SPOOLED";
            }
        }

        return "NOT_DURABLE";
    }

    private void pushLiveUpdate(String phase, String jobId, String stepId, List<Map<String, Object>> changes, List<Map<String, Object>> jobDiagnostics) {
        if (processLogs == null) {
            return;
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("phase", phase);
            payload.put("changes", Map.of(stepId, changes != null ? changes : List.of()));
            payload.put("jobDiagnostics", Map.of(stepId, jobDiagnostics != null ? jobDiagnostics : List.of()));
            processLogs.sendStructuredUpdate(Integer.valueOf(jobId), stepId, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("Unable to push live structured update for job {} step {}: {}", jobId, stepId, e.getMessage());
        }
    }

    void publishPlanProgress(String organizationId, String jobId, String stepId, List<Map<String, Object>> liveChanges, List<Map<String, Object>> jobDiagnostics) {
        publishPlanStructured(organizationId, jobId, stepId, liveChanges, jobDiagnostics, false);
    }

    /**
     * Persist the last word on a plan step - attempted for both a successful and a failed plan, so
     * the UI can show real diagnostics (e.g. an unset required variable) instead of a stale "no
     * changes" state.
     */
    public void publishFinalPlanSnapshot(String organizationId, String jobId, String stepId,
                                         List<Map<String, Object>> liveChanges, List<Map<String, Object>> jobDiagnostics) {
        publishPlanStructured(organizationId, jobId, stepId, liveChanges, jobDiagnostics, true);
    }

    private void publishPlanStructured(String organizationId, String jobId, String stepId,
                                       List<Map<String, Object>> liveChanges, List<Map<String, Object>> jobDiagnostics,
                                       boolean finalSnapshot) {
        if (executorFlagsProperties.isAsyncStructuredOutput() && persistenceQueue != null) {
            try {
                StructuredSnapshot snapshot = StructuredSnapshot.copyOf(organizationId, jobId, stepId,
                        StructuredSnapshot.Phase.PLAN, persistenceQueue.nextSequence(jobId), finalSnapshot,
                        liveChanges, jobDiagnostics, objectMapper);
                persistenceQueue.submit(snapshot);
            } catch (StructuredSnapshot.SnapshotSerializationException e) {
                persistenceQueue.dropSerialization();
            } catch (RuntimeException e) {
                log.warn("Unable to enqueue structured plan snapshot for job {} step {}", jobId, stepId, e);
            }
            return;
        }

        try {
            jobContextService.patchContextFresh(organizationId, jobId,
                    buildPlanPatch(stepId, liveChanges, jobDiagnostics, finalSnapshot));
        } catch (Exception e) {
            log.warn("Unable to publish live plan progress for job {} step {}", jobId, stepId, e);
        }
    }

    List<Map<String, Object>> mergeShowJsonDiff(List<Map<String, Object>> liveChanges, String planJson) throws IOException {
        List<Map<String, Object>> diffChangesByAddress = buildChangesFromPlanJson(planJson);
        Map<Object, Map<String, Object>> diffByAddress = new HashMap<>();
        for (Map<String, Object> diffChange : diffChangesByAddress) {
            diffByAddress.put(diffChange.get("address"), diffChange);
        }

        for (Map<String, Object> liveChange : liveChanges) {
            Map<String, Object> diffChange = diffByAddress.remove(liveChange.get("address"));
            if (diffChange != null) {
                liveChange.putAll(diffChange);
            }
        }

        // Anything show-json found that the live stream never saw a planned_change for (shouldn't
        // normally happen, but a defensive fallback beats silently dropping a resource) gets
        // appended as a new entry.
        liveChanges.addAll(diffByAddress.values());

        return liveChanges;
    }

    String getPlanAsJson(TerraformJob terraformJob, File terraformWorkingDir) throws IOException, InterruptedException, ExecutionException {
        TextStringBuilder planOutput = new TextStringBuilder();
        TextStringBuilder planErrorOutput = new TextStringBuilder();

        TerraformProcessData terraformProcessData = TerraformProcessData
                .builder()
                .terraformVersion(terraformJob.getTerraformVersion())
                .workingDirectory(terraformWorkingDir)
                .detailExitCode(true)
                .tofu(terraformJob.isTofu())
                // Without this, `show -json` runs with none of the credentials (backend auth,
                // dynamic cloud credentials) the main plan process was given via
                // TerraformExecutorServiceImpl.loadTempEnvironmentVariables, and fails against
                // any backend/provider that needs them even though the plan itself just
                // succeeded in the same working directory.
                .terraformEnvironmentVariables(terraformJob.getEnvironmentVariables())
                .build();

        boolean success = terraformClient.showPlanJson(terraformProcessData, (Consumer<String>) planOutput::append, (Consumer<String>) planErrorOutput::append).get();

        if (!success) {
            log.warn("Unable to get plan json for job {} step {}. Error: {}", terraformJob.getJobId(), terraformJob.getStepId(), planErrorOutput);
            return null;
        }

        return planOutput.toString();
    }

    // -json mode never puts the classic attribute-level diff anywhere in the live event stream
    // (only terse one-line "planned_change" summaries) - that diff has only ever existed in
    // Terraform's human-text renderer. Rendering it here, post-hoc from the already-computed
    // plan file, keeps the live JSON stream (structured panel, diagnostics, ephemeral resources,
    // etc.) while restoring the full diff for the console/raw-log/PR-comment consumers that
    // depend on it - PrCommentService.fetchStepOutputText in particular reads this same step's
    // console output expecting exactly this content.
    String getPlanAsHumanText(TerraformJob terraformJob, File terraformWorkingDir) throws IOException, InterruptedException, ExecutionException {
        TextStringBuilder planOutput = new TextStringBuilder();
        TextStringBuilder planErrorOutput = new TextStringBuilder();

        TerraformProcessData terraformProcessData = TerraformProcessData
                .builder()
                .terraformVersion(terraformJob.getTerraformVersion())
                .workingDirectory(terraformWorkingDir)
                .detailExitCode(true)
                .tofu(terraformJob.isTofu())
                .terraformEnvironmentVariables(terraformJob.getEnvironmentVariables())
                .build();

        // appendln (not append): showPlan's Consumer<String> is invoked once per line, same as
        // plan()/apply()'s JSON line consumers - append with no separator would concatenate every
        // line together with nothing between them, producing one unreadable run-on line.
        boolean success = terraformClient.showPlan(terraformProcessData, (Consumer<String>) planOutput::appendln, (Consumer<String>) planErrorOutput::appendln).get();

        if (!success) {
            log.warn("Unable to render human-readable plan for job {} step {}. Error: {}", terraformJob.getJobId(), terraformJob.getStepId(), planErrorOutput);
            return null;
        }

        return planOutput.toString();
    }

    /**
     * Persists {@link #getPlanAsHumanText}'s text under its own context key, separately from the
     * console stream it's also written to, so PrCommentService can read just the diff (#3673).
     * A failure here is logged and swallowed - the console copy is unaffected either way.
     */
    void publishRenderedPlanText(String organizationId, String jobId, String stepId, String renderedText) {
        if (renderedText == null || renderedText.isBlank()) {
            return;
        }

        try {
            jobContextService.patchContextFresh(organizationId, jobId,
                    Map.of(CONTEXT_PLAN_RENDERED_TEXT_KEY + "/" + stepId, renderedText));
        } catch (Exception e) {
            log.warn("Unable to persist rendered plan text for job {} step {}: {}", jobId, stepId, e.getMessage());
        }
    }

    List<Map<String, Object>> buildChangesFromPlanJson(String json) throws IOException {
        Map<String, Object> plan = objectMapper.readValue(json, new TypeReference<>() {
        });
        List<Map<String, Object>> resourceChanges = (List<Map<String, Object>>) plan.getOrDefault("resource_changes", new ArrayList<>());

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> change : resourceChanges) {
            Map<String, Object> changeBlock = (Map<String, Object>) change.get("change");
            if (changeBlock == null) {
                continue;
            }

            List<String> actions = (List<String>) changeBlock.getOrDefault("actions", List.of());
            String action = normalizeAction(actions);
            Object importingValue = changeBlock.get("importing");
            boolean isImporting = importingValue != null;
            if ("no-op".equals(action) && !isImporting) {
                continue;
            }
            if ("no-op".equals(action) && isImporting) {
                action = "import";
            }

            Map<String, Object> entry = new HashMap<>();
            entry.put("address", change.get("address"));
            entry.put("moduleAddress", change.get("module_address"));
            entry.put("resourceType", change.get("type"));
            entry.put("resourceName", change.get("name"));
            entry.put("actions", actions);
            entry.put("action", action);
            if (isImporting) {
                entry.put("importing", importingValue);
            }
            Object beforeValue = changeBlock.get("before");
            Object afterValue = changeBlock.get("after");
            Object beforeSensitive = normalizeResourceSensitivities(
                    (String) change.get("type"),
                    (String) change.get("address"),
                    changeBlock.get("before_sensitive"));
            Object afterSensitive = normalizeResourceSensitivities(
                    (String) change.get("type"),
                    (String) change.get("address"),
                    changeBlock.get("after_sensitive"));
            Object changedSensitive = collectChangedSensitivePaths(
                    beforeValue,
                    afterValue,
                    beforeSensitive,
                    afterSensitive);
            entry.put("before", sanitizeSensitiveValues(beforeValue, beforeSensitive));
            entry.put("beforeSensitive", beforeSensitive);
            entry.put("after", sanitizeSensitiveValues(afterValue, afterSensitive));
            entry.put("afterSensitive", afterSensitive);
            if (changedSensitive != null) {
                entry.put("changedSensitive", changedSensitive);
            }
            entry.put("afterUnknown", changeBlock.get("after_unknown"));
            result.add(entry);
        }
        result.addAll(buildOutputChangeEntries(plan));
        return result;
    }

    // output_changes is a map keyed by output name, unlike resource_changes - each value is the
    // change representation directly (no nested "change" wrapper). A plan with zero resource
    // changes can still change an output (e.g. one derived from a data source), which otherwise
    // renders identically to a genuine no-op plan with no way to tell the difference (#3675).
    private List<Map<String, Object>> buildOutputChangeEntries(Map<String, Object> plan) {
        Object outputChangesRaw = plan.get("output_changes");
        if (!(outputChangesRaw instanceof Map<?, ?> outputChanges)) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<?, ?> outputChange : outputChanges.entrySet()) {
            String outputName = String.valueOf(outputChange.getKey());
            if (!(outputChange.getValue() instanceof Map<?, ?> changeBlockRaw)) {
                continue;
            }
            Map<String, Object> changeBlock = new HashMap<>();
            changeBlockRaw.forEach((k, v) -> changeBlock.put(String.valueOf(k), v));

            Object actionsRaw = changeBlock.get("actions");
            List<String> actions = actionsRaw instanceof List<?> actionList
                    ? actionList.stream().map(String::valueOf).toList()
                    : List.of();
            String action = normalizeAction(actions);
            if ("no-op".equals(action)) {
                continue;
            }

            Map<String, Object> entry = new HashMap<>();
            entry.put("address", outputName);
            entry.put("moduleAddress", null);
            entry.put("resourceType", "output");
            entry.put("resourceName", outputName);
            entry.put("actions", actions);
            entry.put("action", action);
            entry.put("isOutputChange", true);

            Object beforeValue = changeBlock.get("before");
            Object afterValue = changeBlock.get("after");
            Object beforeSensitive = changeBlock.get("before_sensitive");
            Object afterSensitive = changeBlock.get("after_sensitive");
            Object changedSensitive = collectChangedSensitivePaths(beforeValue, afterValue, beforeSensitive, afterSensitive);
            entry.put("before", sanitizeSensitiveValues(beforeValue, beforeSensitive));
            entry.put("beforeSensitive", beforeSensitive);
            entry.put("after", sanitizeSensitiveValues(afterValue, afterSensitive));
            entry.put("afterSensitive", afterSensitive);
            if (changedSensitive != null) {
                entry.put("changedSensitive", changedSensitive);
            }
            entry.put("afterUnknown", changeBlock.get("after_unknown"));
            result.add(entry);
        }
        return result;
    }

    /**
     * Context patch entries for this step. A final write also sets or clears (null) the
     * {@code noChangePlan} marker, with its plan step id, so the UI can treat the associated
     * standard apply as a valid no-op without inferring it from an empty array or console text.
     * Progress writes never touch the marker, and it is never set if the plan encountered errors
     * or has error diagnostics.
     */
    Map<String, Object> buildPlanPatch(String stepId, List<Map<String, Object>> changes,
                                       List<Map<String, Object>> jobDiagnostics, boolean finalSnapshot) {
        // LinkedHashMap, not Map.of: values may be null.
        Map<String, Object> set = new LinkedHashMap<>();
        set.put(CONTEXT_PLAN_KEY + "/" + stepId, changes);
        set.put(CONTEXT_UI_KEY + "/" + stepId, STRUCTURED_PLAN_MARKER);
        set.put(CONTEXT_JOB_DIAGNOSTICS_KEY + "/" + stepId, jobDiagnostics);
        if (finalSnapshot) {
            boolean noChanges = !hasPlanErrors(changes, jobDiagnostics) && (changes == null || changes.isEmpty());
            set.put(CONTEXT_NO_CHANGE_PLAN_KEY, noChanges ? Map.of("planStepId", stepId) : null);
        }
        return set;
    }

    private static boolean hasPlanErrors(List<Map<String, Object>> changes, List<Map<String, Object>> jobDiagnostics) {
        return (jobDiagnostics != null && jobDiagnostics.stream().anyMatch(d -> "error".equals(d.get("severity"))))
                || (changes != null && changes.stream().anyMatch(c -> "errored".equals(c.get("status"))));
    }

    private String normalizeAction(List<String> actions) {
        if (actions.contains("delete") && actions.contains("create")) {
            return "replace";
        }

        if (actions.contains("create")) {
            return "create";
        }

        if (actions.contains("delete")) {
            return "delete";
        }

        if (actions.contains("update")) {
            return "update";
        }

        if (actions.contains("read")) {
            return "read";
        }

        if (actions.contains("no-op")) {
            return "no-op";
        }

        return "unknown";
    }

    Object normalizeResourceSensitivities(String resourceType, String address, Object sensitiveRaw) {
        return TerraformSensitivitySanitizer.normalizeResourceSensitivities(resourceType, address, sensitiveRaw);
    }

    Object sanitizeSensitiveValues(Object value, Object sensitiveMetadata) {
        return TerraformSensitivitySanitizer.sanitizeSensitiveValues(value, sensitiveMetadata);
    }

    // Compare raw values before redaction so the UI can hide unchanged secrets
    // without losing truly changed sensitive fields.
    private Object collectChangedSensitivePaths(Object before, Object after, Object beforeSensitive, Object afterSensitive) {
        if (isSensitiveLeaf(beforeSensitive, afterSensitive)) {
            if (valuesAreEqual(before, after)) {
                return null;
            }

            return true;
        }

        if (hasMapShape(before, after, beforeSensitive, afterSensitive)) {
            return collectChangedSensitiveMap(
                    asMap(before),
                    asMap(after),
                    asMap(beforeSensitive),
                    asMap(afterSensitive));
        }

        if (hasListShape(before, after, beforeSensitive, afterSensitive)) {
            return collectChangedSensitiveList(
                    asList(before),
                    asList(after),
                    asList(beforeSensitive),
                    asList(afterSensitive));
        }

        return null;
    }

    private boolean isSensitiveLeaf(Object beforeSensitive, Object afterSensitive) {
        return Boolean.TRUE.equals(beforeSensitive) || Boolean.TRUE.equals(afterSensitive);
    }

    private boolean hasMapShape(Object before, Object after, Object beforeSensitive, Object afterSensitive) {
        return before instanceof Map<?, ?>
                || after instanceof Map<?, ?>
                || beforeSensitive instanceof Map<?, ?>
                || afterSensitive instanceof Map<?, ?>;
    }

    private boolean hasListShape(Object before, Object after, Object beforeSensitive, Object afterSensitive) {
        return before instanceof List<?>
                || after instanceof List<?>
                || beforeSensitive instanceof List<?>
                || afterSensitive instanceof List<?>;
    }

    private Map<?, ?> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }

        return Map.of();
    }

    private List<?> asList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }

        return List.of();
    }

    private Object collectChangedSensitiveMap(
            Map<?, ?> beforeMap,
            Map<?, ?> afterMap,
            Map<?, ?> beforeSensitiveMap,
            Map<?, ?> afterSensitiveMap) {
        Map<String, Object> changedSensitivePaths = new LinkedHashMap<>();

        for (String key : collectChangedSensitiveKeys(beforeMap, afterMap, beforeSensitiveMap, afterSensitiveMap)) {
            Object changedChild = collectChangedSensitivePaths(
                    beforeMap.get(key),
                    afterMap.get(key),
                    beforeSensitiveMap.get(key),
                    afterSensitiveMap.get(key));
            if (changedChild != null) {
                changedSensitivePaths.put(key, changedChild);
            }
        }

        if (changedSensitivePaths.isEmpty()) {
            return null;
        }

        return changedSensitivePaths;
    }

    private Set<String> collectChangedSensitiveKeys(
            Map<?, ?> beforeMap,
            Map<?, ?> afterMap,
            Map<?, ?> beforeSensitiveMap,
            Map<?, ?> afterSensitiveMap) {
        Set<String> keys = new LinkedHashSet<>();
        addMapKeys(keys, beforeMap);
        addMapKeys(keys, afterMap);
        addMapKeys(keys, beforeSensitiveMap);
        addMapKeys(keys, afterSensitiveMap);
        return keys;
    }

    private void addMapKeys(Set<String> keys, Map<?, ?> map) {
        map.keySet().forEach((key) -> keys.add(String.valueOf(key)));
    }

    private Object collectChangedSensitiveList(
            List<?> beforeList,
            List<?> afterList,
            List<?> beforeSensitiveList,
            List<?> afterSensitiveList) {
        int maxLength = Math.max(
                Math.max(beforeList.size(), afterList.size()),
                Math.max(beforeSensitiveList.size(), afterSensitiveList.size()));
        List<Object> changedSensitivePaths = new ArrayList<>();
        boolean hasChanges = false;

        for (int index = 0; index < maxLength; index++) {
            Object changedChild = collectChangedSensitivePaths(
                    getListValue(beforeList, index),
                    getListValue(afterList, index),
                    getListValue(beforeSensitiveList, index),
                    getListValue(afterSensitiveList, index));
            changedSensitivePaths.add(changedChild);
            if (changedChild != null) {
                hasChanges = true;
            }
        }

        if (!hasChanges) {
            return null;
        }

        return changedSensitivePaths;
    }

    private Object getListValue(List<?> list, int index) {
        if (index >= list.size()) {
            return null;
        }

        return list.get(index);
    }

    private boolean valuesAreEqual(Object left, Object right) {
        return objectMapper.valueToTree(left).equals(objectMapper.valueToTree(right));
    }
}
