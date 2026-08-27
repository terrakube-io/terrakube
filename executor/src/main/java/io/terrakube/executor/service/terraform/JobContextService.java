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
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class JobContextService {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final WorkspaceSecurity workspaceSecurity;
    private final ObjectMapper objectMapper;
    private final String terrakubeApiUrl;
    private final TerrakubeClient terrakubeClient;

    public JobContextService(
            WorkspaceSecurity workspaceSecurity,
            ObjectMapper objectMapper,
            @Value("${io.terrakube.api.url}") String terrakubeApiUrl,
            TerrakubeClient terrakubeClient) {
        this.workspaceSecurity = workspaceSecurity;
        this.objectMapper = objectMapper;
        this.terrakubeApiUrl = terrakubeApiUrl;
        this.terrakubeClient = terrakubeClient;
    }

    public Map<String, Object> getCurrentContext(String organizationId, String jobId) {
        HttpURLConnection connection = null;
        try {
            io.terrakube.client.model.organization.job.Job jobInfo = terrakubeClient.getJobById(organizationId, jobId).getData();
            if (jobInfo.getAttributes().getStatus().equals("running")) {
                log.info("Job {} exists, Terrakube should be able to get the context", jobId);
            } else {
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

    public void saveContext(String organizationId, String jobId, Map<String, Object> context) {
        HttpURLConnection connection = null;
        try {
            io.terrakube.client.model.organization.job.Job jobInfo = terrakubeClient.getJobById(organizationId, jobId).getData();
            if (jobInfo.getAttributes().getStatus().equals("running")) {
                log.info("Job {} exists, Terrakube should be able to save the context", jobId);
            } else {
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

    HttpURLConnection buildConnection(String endpoint, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Authorization", "Bearer " + workspaceSecurity.generateAccessToken(1));
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setUseCaches(false);
        return connection;
    }

    String readResponseBody(HttpURLConnection connection) throws IOException {
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
