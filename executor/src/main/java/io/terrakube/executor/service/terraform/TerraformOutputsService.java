package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Slf4j
@Service
public class TerraformOutputsService {

    private static final String CONTEXT_OUTPUTS_KEY = "terraformOutputs";

    private final JobContextService jobContextService;
    private final ObjectMapper objectMapper;

    public TerraformOutputsService(
            JobContextService jobContextService,
            ObjectMapper objectMapper) {
        this.jobContextService = jobContextService;
        this.objectMapper = objectMapper;
    }

    public void publishOutputs(String organizationId, String jobId, String stepId, String outputJson) {
        try {
            List<Map<String, Object>> outputs = buildOutputsFromJson(outputJson);
            if (outputs.isEmpty()) {
                return;
            }

            Map<String, Object> context = getCurrentContext(organizationId, jobId);
            Map<String, Object> updatedContext = updateOutputsContext(context, stepId, outputs);
            saveContext(organizationId, jobId, updatedContext);
        } catch (Exception e) {
            log.warn("Unable to publish terraform outputs for job {} step {}", jobId, stepId, e);
        }
    }

    List<Map<String, Object>> buildOutputsFromJson(String outputJson) throws IOException {
        if (outputJson == null || outputJson.isBlank()) {
            return new ArrayList<>();
        }

        Map<String, Object> rawOutputs = objectMapper.readValue(outputJson, new TypeReference<>() {
        });

        List<Map<String, Object>> outputs = new ArrayList<>();
        new TreeMap<>(rawOutputs).forEach((name, rawEntry) -> {
            if (!(rawEntry instanceof Map<?, ?> entry)) {
                return;
            }

            boolean sensitive = Boolean.TRUE.equals(entry.get("sensitive"));

            Map<String, Object> output = new HashMap<>();
            output.put("name", name);
            output.put("type", entry.get("type"));
            output.put("sensitive", sensitive);
            // Never let a sensitive output's real value leave the executor process, mirroring
            // how PlanStructuredOutputService redacts sensitive attribute values before they
            // ever reach the context blob.
            output.put("value", sensitive ? null : entry.get("value"));
            outputs.add(output);
        });

        return outputs;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> updateOutputsContext(Map<String, Object> context, String stepId, List<Map<String, Object>> outputs) {
        Map<String, Object> updatedContext = new HashMap<>(context);

        Map<String, Object> terraformOutputs = updatedContext.get(CONTEXT_OUTPUTS_KEY) instanceof Map<?, ?> existing
                ? new HashMap<>((Map<String, Object>) existing)
                : new HashMap<>();
        terraformOutputs.put(stepId, outputs);
        updatedContext.put(CONTEXT_OUTPUTS_KEY, terraformOutputs);

        return updatedContext;
    }

    private Map<String, Object> getCurrentContext(String organizationId, String jobId) {
        return jobContextService.getCurrentContext(organizationId, jobId);
    }

    private void saveContext(String organizationId, String jobId, Map<String, Object> context) {
        jobContextService.saveContext(organizationId, jobId, context);
    }
}

