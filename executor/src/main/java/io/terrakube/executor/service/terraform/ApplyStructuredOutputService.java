package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ApplyStructuredOutputService {

    private static final String CONTEXT_PLAN_KEY = "planStructuredOutput";
    private static final String CONTEXT_APPLY_KEY = "applyStructuredOutput";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final WorkspaceSecurity workspaceSecurity;
    private final ObjectMapper objectMapper;
    private final String terrakubeApiUrl;
    private final TerrakubeClient terrakubeClient;

    public ApplyStructuredOutputService(
            WorkspaceSecurity workspaceSecurity,
            ObjectMapper objectMapper,
            @Value("${io.terrakube.api.url}") String terrakubeApiUrl,
            TerrakubeClient terrakubeClient) {
        this.workspaceSecurity = workspaceSecurity;
        this.objectMapper = objectMapper;
        this.terrakubeApiUrl = terrakubeApiUrl;
        this.terrakubeClient = terrakubeClient;
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
            seededChange.put("status", "pending");
            seeded.add(seededChange);
        }

        return seeded;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> updateApplyContext(Map<String, Object> context, String stepId, List<Map<String, Object>> changes) {
        Map<String, Object> updatedContext = new HashMap<>(context);

        Map<String, Object> applyStructuredOutput = updatedContext.get(CONTEXT_APPLY_KEY) instanceof Map<?, ?> existing
                ? new HashMap<>((Map<String, Object>) existing)
                : new HashMap<>();
        applyStructuredOutput.put(stepId, changes);
        updatedContext.put(CONTEXT_APPLY_KEY, applyStructuredOutput);

        return updatedContext;
    }

    void publishApplyProgress(String organizationId, String jobId, String stepId, List<Map<String, Object>> changes) {
        try {
            Map<String, Object> context = getCurrentContext(organizationId, jobId);
            Map<String, Object> updatedContext = updateApplyContext(context, stepId, changes);
            saveContext(organizationId, jobId, updatedContext);
        } catch (Exception e) {
            log.warn("Unable to publish apply structured output for job {} step {}", jobId, stepId, e);
        }
    }

    @SuppressWarnings("unchecked")
    void resolveFinalValues(List<Map<String, Object>> changes, String stateJson) {
        Map<String, Object> resolvedValuesByAddress = new HashMap<>();
        try {
            Map<String, Object> state = objectMapper.readValue(stateJson, new TypeReference<>() {
            });
            Object valuesRaw = state.get("values");
            if (valuesRaw instanceof Map<?, ?> values) {
                Object rootModuleRaw = values.get("root_module");
                if (rootModuleRaw instanceof Map<?, ?> rootModule) {
                    collectResourceValues((Map<String, Object>) rootModule, resolvedValuesByAddress);
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

            Object afterRaw = change.get("after");
            Object afterUnknownRaw = change.get("afterUnknown");
            if (!(afterRaw instanceof Map<?, ?> after) || !(afterUnknownRaw instanceof Map<?, ?> afterUnknown)) {
                continue;
            }

            Object afterSensitiveRaw = change.get("afterSensitive");
            Map<?, ?> afterSensitive = afterSensitiveRaw instanceof Map<?, ?> ? (Map<?, ?>) afterSensitiveRaw : Map.of();

            Map<String, Object> mutableAfter = (Map<String, Object>) after;
            afterUnknown.forEach((key, isUnknown) -> {
                if (!Boolean.TRUE.equals(isUnknown) || !resolvedMap.containsKey(key)) {
                    return;
                }

                // Never let a resolved sensitive value leave the executor process, mirroring
                // PlanStructuredOutputService's sanitize-before-send precedent - the UI already
                // renders this as "sensitive value" either way, so leaving it redacted here has
                // no visible effect except keeping the real value off the wire entirely.
                if (Boolean.TRUE.equals(afterSensitive.get(key))) {
                    return;
                }

                mutableAfter.put(String.valueOf(key), resolvedMap.get(key));
            });
        }
    }

    @SuppressWarnings("unchecked")
    private void collectResourceValues(Map<String, Object> module, Map<String, Object> resolvedValuesByAddress) {
        Object resourcesRaw = module.get("resources");
        if (resourcesRaw instanceof List<?> resources) {
            for (Object resourceRaw : resources) {
                if (!(resourceRaw instanceof Map<?, ?> resource)) {
                    continue;
                }

                Object address = resource.get("address");
                Object values = resource.get("values");
                if (address instanceof String addressString && values instanceof Map<?, ?>) {
                    resolvedValuesByAddress.put(addressString, values);
                }
            }
        }

        Object childModulesRaw = module.get("child_modules");
        if (childModulesRaw instanceof List<?> childModules) {
            for (Object childModuleRaw : childModules) {
                if (childModuleRaw instanceof Map<?, ?> childModule) {
                    collectResourceValues((Map<String, Object>) childModule, resolvedValuesByAddress);
                }
            }
        }
    }

    private Map<String, Object> getCurrentContext(String organizationId, String jobId) {
        HttpURLConnection connection = null;
        try {
            io.terrakube.client.model.organization.job.Job jobInfo = terrakubeClient.getJobById(organizationId, jobId).getData();
            if (!jobInfo.getAttributes().getStatus().equals("running")) {
                throw new IllegalStateException("Job is not running, cannot get context");
            }
            connection = buildConnection(terrakubeApiUrl + "/context/v1/" + jobInfo.getId(), "GET");
            int statusCode = connection.getResponseCode();
            if (statusCode >= 400) {
                log.warn("Unable to read context for job {}. Response status: {}", jobInfo.getId(), statusCode);
                return new HashMap<>();
            }

            String body = readResponseBody(connection);
            if (body == null || body.isBlank()) {
                return new HashMap<>();
            }

            return objectMapper.readValue(body, new TypeReference<>() {
            });
        } catch (Exception ex) {
            log.warn("Unable to read context for job {}", jobId, ex);
            return new HashMap<>();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void saveContext(String organizationId, String jobId, Map<String, Object> context) {
        HttpURLConnection connection = null;
        try {
            io.terrakube.client.model.organization.job.Job jobInfo = terrakubeClient.getJobById(organizationId, jobId).getData();
            if (!jobInfo.getAttributes().getStatus().equals("running")) {
                throw new IllegalStateException("Job is not running, cannot save context");
            }
            connection = buildConnection(terrakubeApiUrl + "/context/v1/" + jobInfo.getId(), "POST");
            connection.setDoOutput(true);
            byte[] data = objectMapper.writeValueAsBytes(context);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(data);
            }

            int statusCode = connection.getResponseCode();
            if (statusCode >= 400) {
                log.warn("Unable to save context for job {}. Response status: {} Body: {}", jobId, statusCode,
                        readResponseBody(connection));
            }
        } catch (Exception e) {
            log.warn("Unable to save context for job {}", jobId, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection buildConnection(String endpoint, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Authorization", "Bearer " + workspaceSecurity.generateAccessToken(1));
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setUseCaches(false);
        return connection;
    }

    private String readResponseBody(HttpURLConnection connection) throws IOException {
        InputStream stream = connection.getErrorStream();
        if (stream == null) {
            stream = connection.getInputStream();
        }

        if (stream == null) {
            return "";
        }

        try (InputStream responseStream = stream) {
            return new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
