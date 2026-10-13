package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class JobContextService {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 15000;

    private final WorkspaceSecurity workspaceSecurity;
    private final ObjectMapper objectMapper;
    private final String terrakubeApiUrl;
    private final TerrakubeClient terrakubeClient;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    @org.springframework.beans.factory.annotation.Autowired
    public JobContextService(
            WorkspaceSecurity workspaceSecurity,
            ObjectMapper objectMapper,
            @Value("${io.terrakube.api.url}") String terrakubeApiUrl,
            TerrakubeClient terrakubeClient,
            @Value("${io.terrakube.executor.context.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${io.terrakube.executor.context.read-timeout-ms:15000}") int readTimeoutMs) {
        this.workspaceSecurity = workspaceSecurity;
        this.objectMapper = objectMapper;
        this.terrakubeApiUrl = terrakubeApiUrl;
        this.terrakubeClient = terrakubeClient;
        this.connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS;
        this.readTimeoutMs = readTimeoutMs > 0 ? readTimeoutMs : DEFAULT_READ_TIMEOUT_MS;
    }

    // Kept for tests and callers that do not tune the timeouts.
    public JobContextService(
            WorkspaceSecurity workspaceSecurity,
            ObjectMapper objectMapper,
            String terrakubeApiUrl,
            TerrakubeClient terrakubeClient) {
        this(workspaceSecurity, objectMapper, terrakubeApiUrl, terrakubeClient,
                DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    /**
     * Read-only: writers use {@link #patchContext} instead. Throws when the context cannot be read;
     * a job that is no longer running reads as empty.
     */
    public Map<String, Object> getCurrentContext(String organizationId, String jobId) {
        HttpURLConnection connection = null;
        try {
            io.terrakube.client.model.organization.job.Job jobInfo = terrakubeClient.getJobById(organizationId, jobId).getData();
            if (jobInfo.getAttributes().getStatus().equals("running")) {
                log.info("Job {} exists, Terrakube should be able to get the context", jobId);
            } else {
                log.warn("Job {} is not running, cannot get context", jobId);
                return new HashMap<>();
            }
            connection = buildConnection(terrakubeApiUrl + "/context/v1/" + jobInfo.getId(), "GET");
            int statusCode = connection.getResponseCode();
            if (statusCode >= 400) {
                throw new IllegalStateException("Unable to read context for job " + jobInfo.getId() + ". Response status: " + statusCode);
            }

            String body = readResponseBody(connection);
            if (body == null || body.isBlank()) {
                return new HashMap<>();
            }

            return objectMapper.readValue(body, new TypeReference<>() {
            });
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to read context for job " + jobId, ex);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public enum PatchResult { APPLIED, SKIPPED, FAILED }

    /**
     * POSTs {@code {revision, set}} to the context patch endpoint. Each {@code set} path is
     * {@code key} or {@code key/child}; a {@code null} value deletes that entry. The API applies an
     * entry only when {@code revision} is newer than the one it last applied for that path, so a
     * retry of the same write is a no-op ({@link PatchResult#SKIPPED}). Never throws.
     *
     * @return {@link PatchResult#FAILED} on any error or HTTP status >= 400, so callers may retry.
     */
    public PatchResult patchContext(String organizationId, String jobId, long revision, Map<String, Object> set) {
        HttpURLConnection connection = null;
        try {
            io.terrakube.client.model.organization.job.Job jobInfo = terrakubeClient.getJobById(organizationId, jobId).getData();
            if (jobInfo.getAttributes().getStatus().equals("running")) {
                log.info("Job {} exists, Terrakube should be able to patch the context", jobId);
            } else {
                log.warn("Job {} is not running, cannot patch context", jobId);
                return PatchResult.FAILED;
            }
            connection = buildConnection(terrakubeApiUrl + "/context/v1/" + jobInfo.getId() + "/patch", "POST");
            connection.setDoOutput(true);
            // HashMap, not Map.of: set values may be null (delete the entry).
            Map<String, Object> body = new HashMap<>();
            body.put("revision", revision);
            body.put("set", set);
            byte[] data = objectMapper.writeValueAsBytes(body);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(data);
            }

            int statusCode = connection.getResponseCode();
            String responseBody = readResponseBody(connection);
            if (statusCode >= 400) {
                log.warn("Unable to patch context for job {}. Response status: {} Body: {}", jobId, statusCode, responseBody);
                return PatchResult.FAILED;
            }
            // No numeric "applied" (empty body, proxy page, an API without the patch endpoint) is
            // not proof the write landed.
            JsonNode applied = objectMapper.readTree(responseBody).get("applied");
            if (applied == null || !applied.canConvertToLong()) {
                log.warn("Unexpected context patch response for job {}. Body: {}", jobId, responseBody);
                return PatchResult.FAILED;
            }
            return applied.asLong() > 0 ? PatchResult.APPLIED : PatchResult.SKIPPED;
        } catch (Exception e) {
            log.warn("Unable to patch context for job {}: {}", jobId, e.getMessage());
            return PatchResult.FAILED;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * {@link #patchContext} with a freshly minted {@link ContextRevision}. Such a revision can only be
     * stale when another executor pod's clock ran ahead of this one, so a SKIPPED result is logged.
     */
    public PatchResult patchContextFresh(String organizationId, String jobId, Map<String, Object> set) {
        PatchResult result = patchContext(organizationId, jobId, ContextRevision.next(), set);
        if (result == PatchResult.SKIPPED) {
            log.warn("Context patch for job {} entries {} was skipped as stale despite a fresh revision; "
                    + "possible clock skew between executor pods", jobId, set.keySet());
        }
        return result;
    }

    HttpURLConnection buildConnection(String endpoint, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
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
