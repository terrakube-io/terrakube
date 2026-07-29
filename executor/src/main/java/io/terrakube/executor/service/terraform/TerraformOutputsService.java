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
import java.util.TreeMap;

@Slf4j
@Service
public class TerraformOutputsService {

    private static final String CONTEXT_OUTPUTS_KEY = "terraformOutputs";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final WorkspaceSecurity workspaceSecurity;
    private final ObjectMapper objectMapper;
    private final String terrakubeApiUrl;
    private final TerrakubeClient terrakubeClient;

    public TerraformOutputsService(
            WorkspaceSecurity workspaceSecurity,
            ObjectMapper objectMapper,
            @Value("${io.terrakube.api.url}") String terrakubeApiUrl,
            TerrakubeClient terrakubeClient) {
        this.workspaceSecurity = workspaceSecurity;
        this.objectMapper = objectMapper;
        this.terrakubeApiUrl = terrakubeApiUrl;
        this.terrakubeClient = terrakubeClient;
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
