package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.service.terraform.structured.StructuredOutputPersistenceQueue;
import io.terrakube.executor.service.terraform.structured.StructuredSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ApplyStructuredOutputService {

    private static final String CONTEXT_PLAN_KEY = "planStructuredOutput";
    private static final String CONTEXT_APPLY_KEY = "applyStructuredOutput";
    private static final String CONTEXT_JOB_DIAGNOSTICS_KEY = "jobDiagnostics";

    private final JobContextService jobContextService;
    private final ObjectMapper objectMapper;
    private final StructuredOutputPersistenceQueue persistenceQueue;
    private final ExecutorFlagsProperties executorFlagsProperties;

    @Autowired
    public ApplyStructuredOutputService(
            JobContextService jobContextService,
            ObjectMapper objectMapper,
            StructuredOutputPersistenceQueue persistenceQueue,
            ExecutorFlagsProperties executorFlagsProperties) {
        this.jobContextService = jobContextService;
        this.objectMapper = objectMapper;
        this.persistenceQueue = persistenceQueue;
        this.executorFlagsProperties = executorFlagsProperties;
    }

    // Convenience constructor for tests and callers that keep the previous synchronous behaviour.
    public ApplyStructuredOutputService(
            JobContextService jobContextService,
            ObjectMapper objectMapper) {
        this(jobContextService, objectMapper, null, synchronousFlags());
    }

    private static ExecutorFlagsProperties synchronousFlags() {
        ExecutorFlagsProperties flags = new ExecutorFlagsProperties();
        flags.setAsyncStructuredOutput(false);
        return flags;
    }

    public List<Map<String, Object>> seedFromPlan(String organizationId, String jobId) {
        Map<String, Object> context = getCurrentContext(organizationId, jobId);
        return seedFromPlan(context);
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> seedFromPlan(Map<String, Object> context) {
        Object planStructuredOutputRaw = context.get(CONTEXT_PLAN_KEY);
        if (!(planStructuredOutputRaw instanceof Map<?, ?> planStructuredOutput)) {
            return new ArrayList<>();
        }

        if (planStructuredOutput.size() != 1) {
            log.warn("Skipping apply structured output seed: expected exactly one plan step, found {}",
                    planStructuredOutput.size());
            return new ArrayList<>();
        }

        Object soleEntry = planStructuredOutput.values().iterator().next();
        if (!(soleEntry instanceof List<?> planChanges)) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> seeded = new ArrayList<>();
        for (Object rawChange : planChanges) {
            if (!(rawChange instanceof Map<?, ?> change)) {
                continue;
            }

            Map<String, Object> seededChange = new HashMap<>((Map<String, Object>) change);
            // Ephemeral resources have no apply-time lifecycle to reset here when they're never
            // referenced elsewhere in the config: confirmed against a real run that apply -json
            // emits no ephemeral_op/action event for them at all in that case, since their whole
            // open/close lifecycle already happened during planning and apply has nothing that
            // needs the value. Resetting to "pending" would leave the row stuck showing a status
            // apply itself never updates again, even though the job completed successfully -
            // instead keep whatever status it already carried over from the plan.
            if (!"ephemeral".equals(seededChange.get("action"))) {
                seededChange.put("status", "pending");
            }
            seeded.add(seededChange);
        }

        return seeded;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> updateApplyContext(Map<String, Object> context, String stepId, List<Map<String, Object>> changes, List<Map<String, Object>> jobDiagnostics) {
        Map<String, Object> updatedContext = new HashMap<>(context);

        Map<String, Object> applyStructuredOutput = updatedContext.get(CONTEXT_APPLY_KEY) instanceof Map<?, ?> existing
                ? new HashMap<>((Map<String, Object>) existing)
                : new HashMap<>();
        applyStructuredOutput.put(stepId, changes);
        updatedContext.put(CONTEXT_APPLY_KEY, applyStructuredOutput);

        Map<String, Object> jobDiagnosticsByStep = updatedContext.get(CONTEXT_JOB_DIAGNOSTICS_KEY) instanceof Map<?, ?> existingDiagnostics
                ? new HashMap<>((Map<String, Object>) existingDiagnostics)
                : new HashMap<>();
        jobDiagnosticsByStep.put(stepId, jobDiagnostics);
        updatedContext.put(CONTEXT_JOB_DIAGNOSTICS_KEY, jobDiagnosticsByStep);

        return updatedContext;
    }

    void publishApplyProgress(String organizationId, String jobId, String stepId, List<Map<String, Object>> changes, List<Map<String, Object>> jobDiagnostics) {
        publishApplyStructured(organizationId, jobId, stepId, changes, jobDiagnostics, false);
    }

    /** Persist the last word on an apply/destroy step - attempted whether it succeeded or failed. */
    public void publishFinalApplySnapshot(String organizationId, String jobId, String stepId,
                                          List<Map<String, Object>> changes, List<Map<String, Object>> jobDiagnostics) {
        publishApplyStructured(organizationId, jobId, stepId, changes, jobDiagnostics, true);
    }

    private void publishApplyStructured(String organizationId, String jobId, String stepId,
                                        List<Map<String, Object>> changes, List<Map<String, Object>> jobDiagnostics,
                                        boolean finalSnapshot) {
        if (executorFlagsProperties.isAsyncStructuredOutput() && persistenceQueue != null) {
            try {
                StructuredSnapshot snapshot = StructuredSnapshot.copyOf(organizationId, jobId, stepId,
                        StructuredSnapshot.Phase.APPLY, persistenceQueue.nextSequence(), finalSnapshot,
                        changes, jobDiagnostics, objectMapper);
                persistenceQueue.submit(snapshot);
            } catch (StructuredSnapshot.SnapshotSerializationException e) {
                persistenceQueue.dropSerialization();
            } catch (RuntimeException e) {
                log.warn("Unable to enqueue structured apply snapshot for job {} step {}", jobId, stepId, e);
            }
            return;
        }

        try {
            Map<String, Object> context = getCurrentContext(organizationId, jobId);
            Map<String, Object> updatedContext = updateApplyContext(context, stepId, changes, jobDiagnostics);
            saveContext(organizationId, jobId, updatedContext);
        } catch (Exception e) {
            log.warn("Unable to publish apply structured output for job {} step {}", jobId, stepId, e);
        }
    }

    @SuppressWarnings("unchecked")
    void resolveFinalValues(List<Map<String, Object>> changes, String stateJson) {
        Map<String, Object> resolvedValuesByAddress = new HashMap<>();
        Map<String, Object> resolvedSensitiveValuesByAddress = new HashMap<>();
        try {
            Map<String, Object> state = objectMapper.readValue(stateJson, new TypeReference<>() {
            });
            Object valuesRaw = state.get("values");
            if (valuesRaw instanceof Map<?, ?> values) {
                Object rootModuleRaw = values.get("root_module");
                if (rootModuleRaw instanceof Map<?, ?> rootModule) {
                    collectResourceValues((Map<String, Object>) rootModule, resolvedValuesByAddress, resolvedSensitiveValuesByAddress);
                }
            }
        } catch (Exception e) {
            log.warn("Unable to parse current state for apply value resolution", e);
            return;
        }

        for (Map<String, Object> change : changes) {
            Object addressRaw = change.get("address");
            if (!(addressRaw instanceof String address)) {
                continue;
            }

            Object resolvedValues = resolvedValuesByAddress.get(address);
            if (!(resolvedValues instanceof Map<?, ?> resolvedMap)) {
                continue;
            }

            // Config-driven `import` blocks never emit an apply_start/apply_complete hook
            // event over `apply -json` (Terraform calls a separate PreApplyImport/PostApplyImport
            // hook pair that its own JSON view doesn't implement), so ApplyJsonEventParser has
            // nothing to key off of and these rows are stuck at the seeded "pending" status.
            // The resource's presence in the post-apply state is the only signal available that
            // it was actually applied, so use it to unstick anything still marked pending.
            if ("pending".equals(change.get("status"))) {
                change.put("status", "applied");
            }

            Object afterRaw = change.get("after");
            Object afterUnknownRaw = change.get("afterUnknown");
            if (!(afterRaw instanceof Map<?, ?> after) || !(afterUnknownRaw instanceof Map<?, ?> afterUnknown)) {
                continue;
            }

            Object afterSensitiveRaw = change.get("afterSensitive");
            Object stateSensitiveRaw = resolvedSensitiveValuesByAddress.get(address);
            Object mergedSensitiveRaw = normalizeResourceSensitivities(
                    change,
                    mergeSensitiveMetadata(afterSensitiveRaw, stateSensitiveRaw));
            change.put("afterSensitive", mergedSensitiveRaw);

            Map<?, ?> afterSensitive = mergedSensitiveRaw instanceof Map<?, ?> ? (Map<?, ?>) mergedSensitiveRaw : Map.of();

            Map<String, Object> mutableAfter = (Map<String, Object>) after;
            Map<Object, Object> mutableAfterUnknown = (Map<Object, Object>) afterUnknown;

            // Walks every key of the resource's afterUnknown, not just ones that are themselves
            // `true` - an unknown value nested inside a block or list item (one attribute of a
            // network_interface list entry, say) has its own true/false/nested marker several
            // levels down, not at this top level, and previously never got resolved here at all.
            for (Object key : new ArrayList<>(mutableAfterUnknown.keySet())) {
                Object sensitiveChild = Boolean.TRUE.equals(mergedSensitiveRaw) ? Boolean.TRUE : afterSensitive.get(key);
                Object resolvedNewUnknown = resolveUnknownRecursive(
                        mutableAfterUnknown.get(key),
                        mapSlot(mutableAfter, key),
                        resolvedMap.get(key),
                        sensitiveChild);
                mutableAfterUnknown.put(key, resolvedNewUnknown);
            }
        }
    }

    /**
     * Resolves unknown values anywhere within a resource's after/afterUnknown structure - not
     * just at the top level - by walking afterUnknown's shape (which Terraform mirrors exactly
     * against after/afterSensitive: booleans at leaves, nested maps/lists everywhere a block or
     * collection attribute exists) and, at each `true` leaf, splicing the matching real value
     * from post-apply state into `after` via the given slot. Returns the same shape with
     * resolved leaves flipped to `false`, for the caller to store back as the new afterUnknown.
     */
    @SuppressWarnings("unchecked")
    private Object resolveUnknownRecursive(Object afterUnknownNode, ValueSlot afterSlot, Object resolvedNode, Object afterSensitiveNode) {
        if (Boolean.TRUE.equals(afterSensitiveNode)) {
            // If the container or leaf is marked sensitive, never resolve it to plaintext.
            return afterUnknownNode;
        }

        if (Boolean.TRUE.equals(afterUnknownNode)) {
            // Never let a resolved sensitive value leave the executor process, mirroring
            // PlanStructuredOutputService's sanitize-before-send precedent - the UI already
            // renders this as "sensitive value" either way, so leaving it redacted here has no
            // visible effect except keeping the real value off the wire entirely. Also leaves a
            // leaf unresolved (rather than defaulting to something) when state has nothing for
            // it - shouldn't normally happen, but silently keeping "unknown" beats guessing.
            if (resolvedNode == null) {
                return afterUnknownNode;
            }

            afterSlot.set(sanitizeSensitiveValues(resolvedNode, afterSensitiveNode));
            return false;
        }

        if (afterUnknownNode instanceof Map<?, ?> unknownMap) {
            Object currentAfter = afterSlot.get();
            Map<String, Object> afterMap = currentAfter instanceof Map<?, ?> existing
                    ? new HashMap<>((Map<String, Object>) existing)
                    : new HashMap<>();
            Map<?, ?> resolvedMapNode = resolvedNode instanceof Map<?, ?> resolvedMapValue ? resolvedMapValue : Map.of();
            Map<?, ?> sensitiveMapNode = afterSensitiveNode instanceof Map<?, ?> sensitiveMapValue ? sensitiveMapValue : Map.of();

            Map<Object, Object> newUnknownMap = new HashMap<>();
            for (Map.Entry<?, ?> entry : unknownMap.entrySet()) {
                Object key = entry.getKey();
                newUnknownMap.put(key, resolveUnknownRecursive(
                        entry.getValue(), mapSlot(afterMap, key), resolvedMapNode.get(key), sensitiveMapNode.get(key)));
            }
            afterSlot.set(afterMap);
            return newUnknownMap;
        }

        if (afterUnknownNode instanceof List<?> unknownList) {
            Object currentAfter = afterSlot.get();
            List<Object> afterList = currentAfter instanceof List<?> existing ? new ArrayList<>(existing) : new ArrayList<>();
            while (afterList.size() < unknownList.size()) {
                afterList.add(null);
            }
            List<?> resolvedListNode = resolvedNode instanceof List<?> resolvedListValue ? resolvedListValue : List.of();
            List<?> sensitiveListNode = afterSensitiveNode instanceof List<?> sensitiveListValue ? sensitiveListValue : List.of();

            List<Object> newUnknownList = new ArrayList<>();
            for (int index = 0; index < unknownList.size(); index++) {
                Object resolvedChild = index < resolvedListNode.size() ? resolvedListNode.get(index) : null;
                Object sensitiveChild = index < sensitiveListNode.size() ? sensitiveListNode.get(index) : null;
                newUnknownList.add(resolveUnknownRecursive(
                        unknownList.get(index), listSlot(afterList, index), resolvedChild, sensitiveChild));
            }
            afterSlot.set(afterList);
            return newUnknownList;
        }

        // afterUnknown is `false` (already known at plan time) or some other non-boolean,
        // non-container shape this format doesn't define - nothing to resolve either way.
        return afterUnknownNode;
    }

    Object sanitizeSensitiveValues(Object value, Object sensitiveMetadata) {
        return TerraformSensitivitySanitizer.sanitizeSensitiveValues(value, sensitiveMetadata);
    }

    /** A single addressable position inside `after` - either a map entry or a list index. */
    private interface ValueSlot {
        Object get();
        void set(Object value);
    }

    private static ValueSlot mapSlot(Map<String, Object> map, Object key) {
        String stringKey = String.valueOf(key);
        return new ValueSlot() {
            public Object get() {
                return map.get(stringKey);
            }
            public void set(Object value) {
                map.put(stringKey, value);
            }
        };
    }

    private static ValueSlot listSlot(List<Object> list, int index) {
        return new ValueSlot() {
            public Object get() {
                return index < list.size() ? list.get(index) : null;
            }
            public void set(Object value) {
                if (index < list.size()) {
                    list.set(index, value);
                }
            }
        };
    }

    Object mergeSensitiveMetadata(Object planSensitive, Object stateSensitive) {
        return TerraformSensitivitySanitizer.mergeSensitiveMetadata(planSensitive, stateSensitive);
    }

    Object normalizeResourceSensitivities(Map<String, Object> change, Object sensitiveRaw) {
        return TerraformSensitivitySanitizer.normalizeResourceSensitivities(change, sensitiveRaw);
    }

    @SuppressWarnings("unchecked")
    private void collectResourceValues(
            Map<String, Object> module,
            Map<String, Object> resolvedValuesByAddress,
            Map<String, Object> resolvedSensitiveValuesByAddress) {
        Object resourcesRaw = module.get("resources");
        if (resourcesRaw instanceof List<?> resources) {
            for (Object resourceRaw : resources) {
                if (!(resourceRaw instanceof Map<?, ?> resource)) {
                    continue;
                }

                Object address = resource.get("address");
                Object values = resource.get("values");
                Object sensitiveValues = resource.get("sensitive_values");
                if (address instanceof String addressString && values instanceof Map<?, ?>) {
                    resolvedValuesByAddress.put(addressString, values);
                    if (sensitiveValues != null) {
                        resolvedSensitiveValuesByAddress.put(addressString, sensitiveValues);
                    }
                }
            }
        }

        Object childModulesRaw = module.get("child_modules");
        if (childModulesRaw instanceof List<?> childModules) {
            for (Object childModuleRaw : childModules) {
                if (childModuleRaw instanceof Map<?, ?> childModule) {
                    collectResourceValues((Map<String, Object>) childModule, resolvedValuesByAddress, resolvedSensitiveValuesByAddress);
                }
            }
        }
    }

    private Map<String, Object> getCurrentContext(String organizationId, String jobId) {
        return jobContextService.getCurrentContext(organizationId, jobId);
    }

    private void saveContext(String organizationId, String jobId, Map<String, Object> context) {
        jobContextService.saveContext(organizationId, jobId, context);
    }
}

