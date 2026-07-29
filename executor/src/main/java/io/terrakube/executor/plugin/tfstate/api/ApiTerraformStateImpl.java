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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
     * Lifetime (minutes) of the workspace-scoped JWT written as the terraform
     * http-backend {@code password}. Terraform holds this static token for the
     * whole run and re-sends it on the final state write/unlock, so it must
     * outlast the longest plausible apply — but it is scoped to a single
     * workspace, so a leak only exposes that one workspace, and only until it
     * expires. Default 60 minutes.
     */
    @lombok.Builder.Default
    int backendTokenLifetimeMinutes = 60;

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

    /**
     * Seconds between heartbeat pings to the API. Should be well under the
     * API-side lock TTL ({@code io.terrakube.api.http-backend
     * .lock-ttl-seconds}, default 300) so a single missed ping never lets
     * the lock expire. With the defaults a healthy run pings 5 times per
     * TTL window — tolerates 3+ consecutive failures before giving up.
     */
    @lombok.Builder.Default
    long heartbeatIntervalSeconds = 60L;

    /**
     * If no heartbeat has succeeded for this many seconds, the executor
     * aborts the in-flight terraform operation rather than continue to write
     * state the API will reject. Must be smaller than the API lock TTL so
     * we cancel before the lock is reassigned. Default 180s = 3 missed
     * heartbeats at 60s cadence, leaving a 120s margin under the 300s lock
     * TTL for the API to free the lock cleanly.
     */
    @lombok.Builder.Default
    long heartbeatAbortAfterSeconds = 180L;

    private final transient AtomicLong lastSuccessfulHeartbeatMillis = new AtomicLong(0L);
    private final transient AtomicBoolean heartbeatRunning = new AtomicBoolean(false);
    private transient ScheduledExecutorService heartbeatScheduler;
    private transient ScheduledFuture<?> heartbeatTask;

    @Override
    public synchronized void startHeartbeat(String organizationId, String workspaceId) {
        if (!heartbeatRunning.compareAndSet(false, true)) {
            log.warn("Heartbeat already running for workspace {} — startHeartbeat called twice without stop", workspaceId);
            return;
        }
        lastSuccessfulHeartbeatMillis.set(System.currentTimeMillis());
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tf-lock-heartbeat-" + workspaceId);
            thread.setDaemon(true);
            return thread;
        });
        String url = String.format("%s/tfstate/v1/http-backend/organization/%s/workspace/%s/lock/heartbeat",
                apiUrl, organizationId, workspaceId);
        log.info("Starting lock heartbeat for workspace {} every {}s (abort after {}s)",
                workspaceId, heartbeatIntervalSeconds, heartbeatAbortAfterSeconds);
        heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(
                () -> sendHeartbeat(url, workspaceId),
                heartbeatIntervalSeconds, heartbeatIntervalSeconds, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void stopHeartbeat() {
        if (!heartbeatRunning.compareAndSet(true, false)) {
            return;
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }
        log.info("Stopped lock heartbeat");
    }

    @Override
    public void checkHeartbeatHealth() {
        if (!heartbeatRunning.get()) return;
        long lastOk = lastSuccessfulHeartbeatMillis.get();
        long elapsedSec = (System.currentTimeMillis() - lastOk) / 1000L;
        if (elapsedSec > heartbeatAbortAfterSeconds) {
            String message = String.format(
                    "Lost contact with the Terrakube API for %ds (threshold %ds); aborting the run so the workspace lock can be reassigned safely.",
                    elapsedSec, heartbeatAbortAfterSeconds);
            throw new StateUploadFailedException(message);
        }
    }

    private void sendHeartbeat(String url, String workspaceId) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(IO_TIMEOUT_MS);
            conn.setReadTimeout(IO_TIMEOUT_MS);
            // Workspace-scoped, like the backend password — the API binds this token's
            // workspaceId claim to the path workspace, so the heartbeat can only refresh
            // its own lock, not any other workspace's.
            conn.setRequestProperty("Authorization", "Bearer " + workspaceSecurity.generateAccessToken(workspaceId, 5));
            conn.setRequestProperty("Content-Length", "0");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(new byte[0]);
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                lastSuccessfulHeartbeatMillis.set(System.currentTimeMillis());
                log.debug("Heartbeat OK for workspace {}", workspaceId);
            } else if (code == HttpURLConnection.HTTP_GONE) {
                // 410 from the API means the lock TTL elapsed before this ping arrived.
                // Don't update lastSuccessful — let checkHeartbeatHealth abort the run.
                log.warn("Heartbeat for workspace {}: API reports lock-lost (HTTP 410); run will abort on next health check", workspaceId);
            } else {
                log.warn("Heartbeat for workspace {} returned HTTP {}", workspaceId, code);
            }
        } catch (IOException e) {
            log.warn("Heartbeat for workspace {} failed: {}", workspaceId, e.getMessage());
        }
    }

    @Override
    public String getBackendStateFile(String organizationId, String workspaceId, File workingDirectory, String terraformVersion) {
        log.info("Generating http-backend override file for terraform {}", terraformVersion);
        try {
            String stateAddress = String.format("%s/tfstate/v1/http-backend/organization/%s/workspace/%s/state",
                    apiUrl, organizationId, workspaceId);
            String lockAddress = String.format("%s/tfstate/v1/http-backend/organization/%s/workspace/%s/lock",
                    apiUrl, organizationId, workspaceId);

            // Workspace-scoped JWT used as the http-backend password. The API validates the
            // signature against the internal HMAC secret and (companion API change) enforces
            // that the token's workspaceId claim matches the workspace in the request path,
            // so a leaked backend file cannot reach another workspace's state.
            String token = workspaceSecurity.generateAccessToken(workspaceId, backendTokenLifetimeMinutes);

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
            FileUtils.writeStringToFile(backendFile, hcl.toString(), StandardCharsets.UTF_8);
            return BACKEND_FILE_NAME;
        } catch (IOException e) {
            // Air-gapped mode: without this override file terraform would silently fall back
            // to a local backend (or no backend), losing the state route entirely. Fail hard.
            throw new StateUploadFailedException(
                    "Could not write http-backend override file " + BACKEND_FILE_NAME + ": " + e.getMessage(), e);
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

        // historyId is a client-generated UUID; the history PUT endpoint stores bytes at a
        // path derived from it and does not require a pre-existing DB row.
        String stateFilename = UUID.randomUUID().toString();

        // 1) PUT raw + json to the history endpoints, integrity-checked. On hash mismatch or
        //    repeated transport failure the helper throws StateUploadFailedException and the
        //    API never commits the bytes — so we must NOT have written a history row yet, or
        //    it would dangle pointing at an object that was never stored.
        String rawUrl = String.format("%s/tfstate/v1/organization/%s/workspace/%s/history/%s/terraform.tfstate",
                apiUrl, terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(), stateFilename);
        String jsonUrl = String.format("%s/tfstate/v1/organization/%s/workspace/%s/history/%s/terraform.json.tfstate",
                apiUrl, terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(), stateFilename);
        putBytesWithIntegrity(rawUrl, rawState.getBytes(StandardCharsets.UTF_8), "application/json", "state history (raw)", verifyReadBack);
        putBytesWithIntegrity(jsonUrl, applyJSON.getBytes(StandardCharsets.UTF_8), "application/json", "state history (json)", verifyReadBack);

        // 2) only now record the history row, so it can only ever reference bytes that landed.
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
     * PUT the payload with an X-Content-Sha256 header so the API can verify the
     * announced hash against the bytes it received before committing. Retries up
     * to MAX_UPLOAD_ATTEMPTS on transport failures and on HTTP 409 (server-detected
     * hash mismatch). If every attempt fails, throws {@link StateUploadFailedException}
     * with a user-facing message — the caller marks the job failed so the operator
     * sees what went wrong.
     *
     * When {@code readBack} is true a successful PUT is followed by an independent
     * {@link #verifyReadBack} pass. A transient GET hiccup there does NOT re-PUT the
     * (already committed) body or fail the job — only a confirmed hash mismatch does,
     * since the announced X-Content-Sha256 already covers transit integrity.
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
                    if (attempt > 1) {
                        log.info("Upload of {} accepted by API on retry {}/{}", label, attempt, MAX_UPLOAD_ATTEMPTS);
                    } else {
                        log.info("Upload of {} accepted by API (sha256={})", label, sha);
                    }
                    // PUT committed; read-back is a separate concern with its own retry policy.
                    if (readBack) {
                        verifyReadBack(urlString, sha, label);
                    }
                    return;
                }
                lastDetail = readErrorBody(conn);
                log.warn("Upload of {} attempt {}/{} returned HTTP {} (detail={})",
                        label, attempt, MAX_UPLOAD_ATTEMPTS, lastCode, lastDetail);
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
     * After a committed PUT, GET the same URL back and compare hashes. The GET is
     * retried independently of the upload so a transient read hiccup never re-PUTs
     * the body. The job fails ONLY on a confirmed hash mismatch — the server is
     * serving different bytes than we sent, which a re-PUT would not fix. If every
     * read-back attempt fails to complete (transport/non-2xx), we log and accept
     * the upload: the announced X-Content-Sha256 the API already validated covers
     * transit integrity.
     */
    private void verifyReadBack(String urlString, String expectedSha, String label) {
        for (int attempt = 1; attempt <= MAX_UPLOAD_ATTEMPTS; attempt++) {
            String observed = fetchAndHash(urlString);
            if (observed == null) {
                log.warn("Read-back GET for {} could not complete on attempt {}/{}", label, attempt, MAX_UPLOAD_ATTEMPTS);
                if (attempt < MAX_UPLOAD_ATTEMPTS) {
                    sleepBackoff(attempt);
                }
                continue;
            }
            if (observed.equalsIgnoreCase(expectedSha)) {
                log.info("Upload of {} verified end-to-end (sha256={})", label, expectedSha);
                return;
            }
            String message = String.format(
                    "Read-back verification of %s at %s failed (read-back mismatch: expected=%s, observed=%s): "
                            + "the server stored different bytes than were sent. Workspace state may be corrupt.",
                    label, urlString, expectedSha, observed);
            log.error(message);
            throw new StateUploadFailedException(message);
        }
        log.warn("Could not read {} back after {} attempts to verify; relying on the announced X-Content-Sha256 the API already validated.",
                label, MAX_UPLOAD_ATTEMPTS);
    }

    /**
     * GET the given URL and return the hex sha-256 of the body. Returns
     * {@code null} on any transport error or non-2xx response so the caller
     * can retry the read independently of the upload.
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
}
