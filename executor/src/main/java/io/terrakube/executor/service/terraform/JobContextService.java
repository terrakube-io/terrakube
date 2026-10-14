package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.RetryableException;
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
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class JobContextService {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 15000;
    private static final int FRESH_PATCH_ATTEMPTS = 3;
    // Test seam: set once per JVM, after the first fallback write landed.
    static final AtomicBoolean LEGACY_WARNED = new AtomicBoolean();

    private final WorkspaceSecurity workspaceSecurity;
    private final ObjectMapper objectMapper;
    private final String terrakubeApiUrl;
    private final TerrakubeClient terrakubeClient;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    // Test seam: base backoff between patchContextFresh attempts (doubles each retry).
    volatile long retryBackoffMs = 500;

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
        try {
            io.terrakube.client.model.organization.job.Job jobInfo = terrakubeClient.getJobById(organizationId, jobId).getData();
            if (jobInfo.getAttributes().getStatus().equals("running")) {
                log.info("Job {} exists, Terrakube should be able to get the context", jobId);
            } else {
                log.warn("Job {} is not running, cannot get context", jobId);
                return new HashMap<>();
            }
            return readContext(jobInfo.getId());
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to read context for job " + jobId, ex);
        }
    }

    private Map<String, Object> readContext(String jobId) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = buildConnection(terrakubeApiUrl + "/context/v1/" + jobId, "GET");
            int statusCode = connection.getResponseCode();
            if (statusCode >= 400) {
                throw new IllegalStateException("Unable to read context for job " + jobId + ". Response status: " + statusCode);
            }

            String body = readResponseBody(connection);
            if (body == null || body.isBlank()) {
                return new HashMap<>();
            }

            return objectMapper.readValue(body, new TypeReference<>() {
            });
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public enum PatchResult { APPLIED, SKIPPED, FAILED }

    /** {@code retryable}: a FAILED worth retrying, an IO error or timeout, HTTP 5xx, 408 or 429. */
    record Attempt(PatchResult result, boolean retryable) {
    }

    private static boolean retryable(int statusCode) {
        return statusCode >= 500 || statusCode == 408 || statusCode == 429;
    }

    /**
     * POSTs {@code {revision, set}} to the context patch endpoint. Each {@code set} path is
     * {@code key} or {@code key/child}; a {@code null} value deletes that entry. The API applies an
     * entry only when {@code revision} is newer than the one it last applied for that path, so a
     * retry of the same write is a no-op ({@link PatchResult#SKIPPED}). Never throws.
     *
     * <p>An API without the patch endpoint (HTTP 404/405) gets {@link #legacyMerge} instead.
     *
     * @return {@link PatchResult#FAILED} on any error or HTTP status >= 400, so callers may retry.
     */
    public PatchResult patchContext(String organizationId, String jobId, long revision, Map<String, Object> set) {
        return attemptPatch(organizationId, jobId, revision, set).result();
    }

    Attempt attemptPatch(String organizationId, String jobId, long revision, Map<String, Object> set) {
        HttpURLConnection connection = null;
        try {
            io.terrakube.client.model.organization.job.Job jobInfo = terrakubeClient.getJobById(organizationId, jobId).getData();
            if (jobInfo.getAttributes().getStatus().equals("running")) {
                log.info("Job {} exists, Terrakube should be able to patch the context", jobId);
            } else {
                log.warn("Job {} is not running, cannot patch context", jobId);
                return new Attempt(PatchResult.FAILED, false);
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
            // A 404 with "{}" is the patch endpoint itself answering for a missing job.
            if ((statusCode == 404 && !"{}".equals(responseBody)) || statusCode == 405) {
                return legacyMerge(jobInfo.getId(), set);
            }
            if (statusCode >= 400) {
                log.warn("Unable to patch context for job {}. Response status: {} Body: {}", jobId, statusCode, responseBody);
                return new Attempt(PatchResult.FAILED, retryable(statusCode));
            }
            // No numeric "applied" (empty body, proxy page, an API without the patch endpoint) is
            // not proof the write landed.
            JsonNode applied;
            try {
                JsonNode response = objectMapper.readTree(responseBody);
                applied = response == null ? null : response.get("applied");
            } catch (JsonProcessingException e) {
                applied = null;
            }
            if (applied == null || !applied.canConvertToLong()) {
                log.warn("Unexpected context patch response for job {}. Body: {}", jobId, responseBody);
                return new Attempt(PatchResult.FAILED, false);
            }
            return new Attempt(applied.asLong() > 0 ? PatchResult.APPLIED : PatchResult.SKIPPED, false);
        } catch (Exception e) {
            log.warn("Unable to patch context for job {}: {}", jobId, e.getMessage());
            boolean retryable = e instanceof IOException && !(e instanceof JsonProcessingException)
                    || e instanceof RetryableException
                    || e instanceof FeignException feignException && retryable(feignException.status());
            return new Attempt(PatchResult.FAILED, retryable);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Read-modify-write of the whole document for an API that predates the patch endpoint. It
     * ignores revisions, so writers on different executors can lose updates - the behaviour of that
     * API anyway. Synchronized so this executor's own writers (the persistence queue and the job
     * thread) do not.
     */
    @SuppressWarnings("unchecked")
    private synchronized Attempt legacyMerge(String jobId, Map<String, Object> set) throws IOException {
        Map<String, Object> context = readContext(jobId);
        set.forEach((path, value) -> {
            String[] parts = path.split("/", 2);
            if (parts.length == 1) {
                if (value == null) {
                    context.remove(path);
                } else {
                    context.put(path, value);
                }
            } else if (value == null) {
                // A delete never creates or replaces the parent, as on the API.
                if (context.get(parts[0]) instanceof Map<?, ?> parent) {
                    parent.remove(parts[1]);
                }
            } else {
                if (!(context.get(parts[0]) instanceof Map)) {
                    context.put(parts[0], new HashMap<String, Object>());
                }
                ((Map<String, Object>) context.get(parts[0])).put(parts[1], value);
            }
        });

        HttpURLConnection connection = null;
        try {
            connection = buildConnection(terrakubeApiUrl + "/context/v1/" + jobId, "POST");
            connection.setDoOutput(true);
            byte[] data = objectMapper.writeValueAsBytes(context);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(data);
            }
            int statusCode = connection.getResponseCode();
            if (statusCode >= 400) {
                log.warn("Unable to save context for job {}. Response status: {} Body: {}", jobId, statusCode,
                        readResponseBody(connection));
                return new Attempt(PatchResult.FAILED, retryable(statusCode));
            }
            if (LEGACY_WARNED.compareAndSet(false, true)) {
                log.warn("API has no context patch endpoint; falling back to full-document writes - upgrade the API");
            }
            return new Attempt(PatchResult.APPLIED, false);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * {@link #patchContext} with a freshly minted {@link ContextRevision}, retried with the same
     * revision on a retryable FAILED (so a retry of a write that did land is a no-op). A fresh
     * revision is only stale when an equal or newer one already landed, so a SKIPPED first attempt
     * is logged; on a retry it most likely means our own earlier attempt landed and only its
     * response was lost.
     */
    public PatchResult patchContextFresh(String organizationId, String jobId, Map<String, Object> set) {
        long revision = ContextRevision.next(jobId);
        Attempt attempt = attemptPatch(organizationId, jobId, revision, set);
        int attempts = 1;
        while (attempt.result() == PatchResult.FAILED && attempt.retryable() && attempts < FRESH_PATCH_ATTEMPTS) {
            try {
                Thread.sleep(retryBackoffMs << (attempts - 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return attempt.result();
            }
            attempt = attemptPatch(organizationId, jobId, revision, set);
            attempts++;
        }
        if (attempt.result() == PatchResult.SKIPPED) {
            if (attempts == 1) {
                log.warn("Context patch for job {} entries {} was skipped: an equal or newer revision of this entry already landed",
                        jobId, set.keySet());
            } else {
                log.debug("Context patch retry for job {} entries {} was skipped: an earlier attempt most likely landed",
                        jobId, set.keySet());
            }
        }
        return attempt.result();
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
