package io.terrakube.executor.plugin.tfstate.api;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.workspace.history.History;
import io.terrakube.client.model.organization.workspace.history.HistoryAttributes;
import io.terrakube.client.model.organization.workspace.history.HistoryRequest;
import io.terrakube.executor.plugin.tfstate.StateUploadFailedException;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.text.TextStringBuilder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TerraformState implementation that routes every state/plan/output write to
 * the Terrakube API server over HTTPS. The agent (executor) needs no direct
 * network access to the configured object store — the API process is the only
 * thing that talks to GCS/S3/Azure/Local.
 *
 * - Live state lives behind Terraform's native "http" backend (so terraform
 *   itself reads/writes through the API).
 * - Plan binaries, step output, and history state snapshots are PUT to the
 *   matching API endpoints.
 */
@Slf4j
@Builder
public class ApiTerraformStateImpl implements TerraformState {

    private static final String TERRAFORM_PLAN_FILE = "terraformLibrary.tfPlan";
    private static final String BACKEND_FILE_NAME = "api_backend_override.tf";
    private static final int TOKEN_LIFETIME_MINUTES = 60 * 24; // 1 day, comfortably longer than any single job
    private static final int IO_TIMEOUT_MS = 60_000;
    private static final int MAX_UPLOAD_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 500L;

    @NonNull
    TerrakubeClient terrakubeClient;

    @NonNull
    TerraformStatePathService terraformStatePathService;

    @NonNull
    TerraformOutputPathService terraformOutputPathService;

    @NonNull
    WorkspaceSecurity workspaceSecurity;

    @NonNull
    String apiUrl;

    /**
     * When {@code true} the executor GETs each just-PUT object back from the API
     * and re-hashes it locally. This provides independent end-to-end
     * confirmation that what the storage backend committed equals what the
     * executor produced, closing the loop the header-only check can't:
     * the {@code X-Content-Sha256} header travels alongside the body from the
     * same producer, so on its own it can detect transit corruption but not a
     * faulty/compromised producer. Disable only for cost-sensitive workspaces
     * where doubling the upload byte count matters; integrity drops to "TCP +
     * TLS + announced hash" without it.
     */
    @lombok.Builder.Default
    boolean verifyReadBack = true;

    @Override
    public String getBackendStateFile(String organizationId, String workspaceId, File workingDirectory, String terraformVersion) {
        log.info("Generating http-backend override file for terraform {}", terraformVersion);
        try {
            String stateAddress = String.format("%s/tfstate/v1/http-backend/organization/%s/workspace/%s/state",
                    apiUrl, organizationId, workspaceId);
            String lockAddress = String.format("%s/tfstate/v1/http-backend/organization/%s/workspace/%s/lock",
                    apiUrl, organizationId, workspaceId);

            // long-lived JWT bound to this workspace; the API validates it against the internal HMAC secret
            String token = workspaceSecurity.generateAccessToken(TOKEN_LIFETIME_MINUTES);

            TextStringBuilder hcl = new TextStringBuilder();
            hcl.appendln("terraform {");
            hcl.appendln("  backend \"http\" {");
            hcl.appendln("    address        = \"" + stateAddress + "\"");
            hcl.appendln("    lock_address   = \"" + lockAddress + "\"");
            hcl.appendln("    unlock_address = \"" + lockAddress + "\"");
            hcl.appendln("    lock_method    = \"POST\"");
            hcl.appendln("    unlock_method  = \"DELETE\"");
            hcl.appendln("    username       = \"internal\"");
            hcl.appendln("    password       = \"" + token + "\"");
            hcl.appendln("  }");
            hcl.appendln("}");

            File backendFile = new File(
                    FilenameUtils.separatorsToSystem(
                            workingDirectory.getAbsolutePath().concat("/").concat(BACKEND_FILE_NAME)));
            FileUtils.writeStringToFile(backendFile, hcl.toString(), Charset.defaultCharset());
            return BACKEND_FILE_NAME;
        } catch (IOException e) {
            log.error("Could not write http-backend override: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public String saveTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory) {
        File tfPlanContent = new File(FilenameUtils.concat(workingDirectory.getAbsolutePath(), TERRAFORM_PLAN_FILE));
        if (!tfPlanContent.exists()) {
            return null;
        }
        String url = String.format("%s/tfstate/v1/organization/%s/workspace/%s/jobId/%s/step/%s/terraform.tfstate",
                apiUrl, organizationId, workspaceId, jobId, stepId);
        byte[] body;
        try {
            body = FileUtils.readFileToByteArray(tfPlanContent);
        } catch (IOException e) {
            throw new StateUploadFailedException("Could not read local terraform plan file " + tfPlanContent.getAbsolutePath(), e);
        }
        putBytesWithIntegrity(url, body, "application/octet-stream", "terraform plan", verifyReadBack);
        log.info("Uploaded terraform plan: {}", url);
        return url;
    }

    @Override
    public boolean downloadTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory) {
        AtomicBoolean planExists = new AtomicBoolean(false);
        String stateUrl = terrakubeClient.getJobById(organizationId, jobId).getData().getAttributes().getTerraformPlan();
        if (stateUrl == null || stateUrl.isEmpty()) {
            return false;
        }
        try {
            log.info("Downloading plan from {}", stateUrl);
            HttpURLConnection conn = (HttpURLConnection) URI.create(stateUrl).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + workspaceSecurity.generateAccessToken(5));
            conn.setConnectTimeout(IO_TIMEOUT_MS);
            conn.setReadTimeout(IO_TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                File dest = new File(FilenameUtils.concat(workingDirectory.getAbsolutePath(), TERRAFORM_PLAN_FILE));
                FileUtils.copyInputStreamToFile(conn.getInputStream(), dest);
                planExists.set(true);
            } else {
                log.error("Plan download failed, HTTP {}", code);
            }
        } catch (IOException e) {
            log.error("Plan download failed: {}", e.getMessage());
        }
        return planExists.get();
    }

    @Override
    public void saveStateJson(TerraformJob terraformJob, String applyJSON, String rawState) {
        if (applyJSON == null) {
            return;
        }

        // 1) create the history row via the existing API client; it returns a UUID we use as historyId.
        String stateFilename = UUID.randomUUID().toString();

        HistoryRequest historyRequest = new HistoryRequest();
        History newHistory = new History();
        newHistory.setType("history");
        HistoryAttributes historyAttributes = new HistoryAttributes();
        historyAttributes.setJobReference(terraformJob.getJobId());
        historyAttributes.setSerial(1);
        historyAttributes.setMd5("0");
        historyAttributes.setLineage("0");
        historyAttributes.setOutput(terraformStatePathService.getStateJsonPath(
                terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(), stateFilename));
        newHistory.setAttributes(historyAttributes);
        historyRequest.setData(newHistory);

        terrakubeClient.createHistory(historyRequest, terraformJob.getOrganizationId(), terraformJob.getWorkspaceId());

        // 2) PUT raw + json to the new history endpoints, integrity-checked.
        //    On hash mismatch or repeated transport failure the helper throws
        //    StateUploadFailedException and the API never commits the bytes,
        //    so the workspace state remains unchanged.
        String rawUrl = String.format("%s/tfstate/v1/organization/%s/workspace/%s/history/%s/terraform.tfstate",
                apiUrl, terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(), stateFilename);
        String jsonUrl = String.format("%s/tfstate/v1/organization/%s/workspace/%s/history/%s/terraform.json.tfstate",
                apiUrl, terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(), stateFilename);
        putBytesWithIntegrity(rawUrl, rawState.getBytes(StandardCharsets.UTF_8), "application/json", "state history (raw)", verifyReadBack);
        putBytesWithIntegrity(jsonUrl, applyJSON.getBytes(StandardCharsets.UTF_8), "application/json", "state history (json)", verifyReadBack);
        log.info("State history uploaded for workspace {} (historyId={})", terraformJob.getWorkspaceId(), stateFilename);
    }

    @Override
    public String saveOutput(String organizationId, String jobId, String stepId, String output, String outputError) {
        String url = String.format("%s/tfoutput/v1/organization/%s/job/%s/step/%s",
                apiUrl, organizationId, jobId, stepId);
        byte[] body = (output + outputError).getBytes(StandardCharsets.UTF_8);
        // Step output is diagnostic and the GET endpoint blends in live Redis-streamed
        // logs, so a byte-for-byte read-back wouldn't be meaningful here. Header-only.
        putBytesWithIntegrity(url, body, "text/plain", "step output", false);
        log.info("Step output uploaded via API: {}", url);
        return terraformOutputPathService.getOutputPath(organizationId, jobId, stepId);
    }

    /**
     * PUT the payload with an X-Content-Sha256 header so the API can verify
     * what it received before committing. Retries up to MAX_UPLOAD_ATTEMPTS on
     * transport failures and on HTTP 409 (server-detected hash mismatch). If
     * every attempt fails, throws {@link StateUploadFailedException} with a
     * user-facing message — the caller is responsible for marking the job as
     * failed so the operator sees what went wrong.
     */
    /**
     * PUT the payload with an X-Content-Sha256 header so the API can verify
     * the announced hash against the bytes it received. When {@code readBack}
     * is true we additionally GET the same URL after a successful PUT and
     * re-hash the response — this catches the case where the announced hash
     * and the body agree (the API accepts the upload) but the bytes the
     * storage backend actually persisted differ from what we intended. Both
     * failures are retried in the same loop, up to MAX_UPLOAD_ATTEMPTS.
     */
    private void putBytesWithIntegrity(String urlString, byte[] body, String contentType, String label, boolean readBack) {
        String sha = sha256Hex(body);
        IOException lastIo = null;
        int lastCode = -1;
        String lastDetail = null;
        for (int attempt = 1; attempt <= MAX_UPLOAD_ATTEMPTS; attempt++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(IO_TIMEOUT_MS);
                conn.setReadTimeout(IO_TIMEOUT_MS);
                conn.setRequestProperty("Authorization", "Bearer " + workspaceSecurity.generateAccessToken(5));
                conn.setRequestProperty("Content-Type", contentType);
                conn.setRequestProperty("X-Content-Sha256", sha);
                conn.setRequestProperty("Content-Length", Integer.toString(body.length));
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
                lastCode = conn.getResponseCode();
                if (lastCode >= 200 && lastCode < 300) {
                    if (readBack) {
                        String observed = fetchAndHash(urlString);
                        if (observed == null) {
                            lastDetail = "read-back GET failed";
                            log.warn("Read-back GET failed for {} on attempt {}/{}", label, attempt, MAX_UPLOAD_ATTEMPTS);
                        } else if (!observed.equalsIgnoreCase(sha)) {
                            lastCode = -2;
                            lastDetail = String.format("read-back hash mismatch (expected=%s, observed=%s)", sha, observed);
                            log.warn("Read-back integrity check FAILED for {}: {}", label, lastDetail);
                        } else {
                            if (attempt > 1) {
                                log.info("Upload of {} succeeded with read-back verification on retry {}/{}", label, attempt, MAX_UPLOAD_ATTEMPTS);
                            } else {
                                log.info("Upload of {} verified end-to-end (sha256={})", label, sha);
                            }
                            return;
                        }
                    } else {
                        if (attempt > 1) {
                            log.info("Upload of {} succeeded on retry {}/{}", label, attempt, MAX_UPLOAD_ATTEMPTS);
                        }
                        return;
                    }
                } else {
                    lastDetail = readErrorBody(conn);
                    log.warn("Upload of {} attempt {}/{} returned HTTP {} (detail={})",
                            label, attempt, MAX_UPLOAD_ATTEMPTS, lastCode, lastDetail);
                }
            } catch (IOException e) {
                lastIo = e;
                log.warn("Upload of {} attempt {}/{} failed: {}", label, attempt, MAX_UPLOAD_ATTEMPTS, e.getMessage());
            }
            if (attempt < MAX_UPLOAD_ATTEMPTS) {
                sleepBackoff(attempt);
            }
        }
        String reason;
        if (lastCode == 409) {
            reason = "API rejected the payload because the sha-256 did not match what was announced (transfer was corrupted in flight)";
        } else if (lastCode == -2) {
            reason = "the server accepted the upload but the read-back GET returned different bytes (" + lastDetail + ")";
        } else if (lastCode > 0) {
            reason = "API responded HTTP " + lastCode + (lastDetail != null ? " (" + lastDetail + ")" : "");
        } else {
            reason = lastIo != null ? "transport error: " + lastIo.getMessage() : "unknown error";
        }
        String message = String.format(
                "Could not upload %s to %s after %d attempts: %s. Workspace state was NOT modified on the server.",
                label, urlString, MAX_UPLOAD_ATTEMPTS, reason);
        throw new StateUploadFailedException(message, lastIo);
    }

    /**
     * GET the given URL and return the hex sha-256 of the body. Returns
     * {@code null} on any transport error or non-2xx response so the caller
     * can treat it as a verification failure indistinguishable from a hash
     * mismatch (both retryable).
     */
    private String fetchAndHash(String urlString) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(IO_TIMEOUT_MS);
            conn.setReadTimeout(IO_TIMEOUT_MS);
            conn.setRequestProperty("Authorization", "Bearer " + workspaceSecurity.generateAccessToken(5));
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                return sha256Hex(IOUtils.toByteArray(in));
            }
        } catch (IOException e) {
            return null;
        }
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(INITIAL_BACKOFF_MS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String readErrorBody(HttpURLConnection conn) {
        InputStream stream = conn.getErrorStream();
        if (stream == null) {
            return null;
        }
        try {
            String body = IOUtils.toString(stream, StandardCharsets.UTF_8);
            return body.length() > 512 ? body.substring(0, 512) + "..." : body;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @SuppressWarnings("unused")
    private static String basicAuthHeader(String user, String token) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + token).getBytes(StandardCharsets.UTF_8));
    }
}
