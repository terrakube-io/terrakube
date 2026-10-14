package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Records, on the job, the concrete version its workspace's version constraint resolved to. The API sends
 * that exact version with every later step, so plan and apply cannot end up on different releases when a
 * constraint such as {@code >= 1.3.0} would resolve differently the second time.
 */
@Slf4j
@Service
public class JobVersionPinner {

    private static final int ATTEMPTS = 3;
    private static final int TIMEOUT_MS = 10000;

    private final WorkspaceSecurity workspaceSecurity;
    private final ObjectMapper objectMapper;
    private final String terrakubeApiUrl;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(TIMEOUT_MS)).build();

    @Autowired
    public JobVersionPinner(WorkspaceSecurity workspaceSecurity, ObjectMapper objectMapper,
                            @Value("${io.terrakube.api.url}") String terrakubeApiUrl) {
        this.workspaceSecurity = workspaceSecurity;
        this.objectMapper = objectMapper;
        this.terrakubeApiUrl = terrakubeApiUrl;
    }

    /**
     * Best effort: a failure is logged and the step carries on with the version it resolved, which is how
     * it behaved before versions were pinned. The API refuses to change a version that is already pinned
     * (400), which is not an error here: the job already has one.
     *
     * @return {@code true} when the API accepted the version or already has it.
     */
    public boolean pin(String organizationId, String jobId, String version) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                String body = objectMapper.writeValueAsString(Map.of("version", version));
                HttpRequest request = HttpRequest.newBuilder(URI.create(
                                terrakubeApiUrl + "/version/v1/organization/" + organizationId + "/job/" + jobId))
                        .timeout(Duration.ofMillis(TIMEOUT_MS))
                        .header("Authorization", "Bearer " + workspaceSecurity.generateAccessToken(1))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status < 300) {
                    return true;
                }
                if (status == 409) {
                    log.info("Job {} is already pinned to another Terraform version; keeping that one", jobId);
                    return true;
                }
                if (status == 400 || status == 403 || status == 404) {
                    // Retrying cannot change these; the step carries on with the version it resolved.
                    log.warn("The API refused to pin version {} on job {}: HTTP {}", version, jobId, status);
                    return false;
                }
                log.warn("Could not pin version {} on job {} (attempt {}): HTTP {}", version, jobId, attempt, status);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (IOException | RuntimeException e) {
                log.warn("Could not pin version {} on job {} (attempt {}): {}", version, jobId, attempt, e.getMessage());
            }
            if (attempt < ATTEMPTS) {
                try {
                    Thread.sleep(250L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
