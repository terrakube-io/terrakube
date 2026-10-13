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

            jobContextService.patchContextFresh(organizationId, jobId,
                    Map.of(CONTEXT_OUTPUTS_KEY + "/" + stepId, outputs));
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
}
