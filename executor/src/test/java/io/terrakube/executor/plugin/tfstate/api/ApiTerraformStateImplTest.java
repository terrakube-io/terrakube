package io.terrakube.executor.plugin.tfstate.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.workspace.history.HistoryRequest;
import io.terrakube.executor.plugin.tfstate.StateUploadFailedException;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiTerraformStateImplTest {

    private TerrakubeClient terrakubeClient;
    private TerraformStatePathService terraformStatePathService;
    private TerraformOutputPathService terraformOutputPathService;
    private WorkspaceSecurity workspaceSecurity;
    private HttpServer server;
    private String apiUrl;
    private final Map<String, byte[]> storedBodies = new HashMap<>();
    private final Map<String, AtomicInteger> putAttempts = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        terrakubeClient = mock(TerrakubeClient.class);
        terraformStatePathService = mock(TerraformStatePathService.class);
        terraformOutputPathService = mock(TerraformOutputPathService.class);
        workspaceSecurity = mock(WorkspaceSecurity.class);
        when(workspaceSecurity.generateAccessToken(anyInt())).thenReturn("test-token");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        apiUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        storedBodies.clear();
        putAttempts.clear();
    }

    private ApiTerraformStateImpl newSubject(boolean verifyReadBack) {
        return ApiTerraformStateImpl.builder()
                .terrakubeClient(terrakubeClient)
                .terraformStatePathService(terraformStatePathService)
                .terraformOutputPathService(terraformOutputPathService)
                .workspaceSecurity(workspaceSecurity)
                .apiUrl(apiUrl)
                .verifyReadBack(verifyReadBack)
                .build();
    }

    /** Honest happy-path handler: stores the body on PUT, serves it back on GET. */
    private HttpHandler echoingStorage() {
        return exchange -> {
            putAttempts.computeIfAbsent(exchange.getRequestURI().getPath(), k -> new AtomicInteger(0));
            if ("PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                putAttempts.get(exchange.getRequestURI().getPath()).incrementAndGet();
                byte[] body = exchange.getRequestBody().readAllBytes();
                String announced = exchange.getRequestHeaders().getFirst("X-Content-Sha256");
                if (announced != null && !announced.equalsIgnoreCase(sha256Hex(body))) {
                    respond(exchange, 409, "{\"error\":\"sha256-mismatch\"}");
                    return;
                }
                storedBodies.put(exchange.getRequestURI().getPath(), body);
                respond(exchange, 201, "");
            } else if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                byte[] body = storedBodies.get(exchange.getRequestURI().getPath());
                if (body == null) {
                    respond(exchange, 404, "");
                } else {
                    exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                }
            } else {
                respond(exchange, 405, "");
            }
        };
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void getBackendStateFileWritesHttpBackendOverride(@TempDir Path tempDir) throws IOException {
        when(workspaceSecurity.generateAccessToken(anyInt())).thenReturn("jwt-token");
        ApiTerraformStateImpl subject = newSubject(true);

        String filename = subject.getBackendStateFile("org-1", "ws-1", tempDir.toFile(), "1.5.0");

        assertEquals("api_backend_override.tf", filename);
        File backendFile = new File(tempDir.toFile(), filename);
        assertTrue(backendFile.exists());
        String hcl = FileUtils.readFileToString(backendFile, Charset.defaultCharset());
        assertTrue(hcl.contains("backend \"http\""));
        assertTrue(hcl.contains("address        = \"" + apiUrl + "/tfstate/v1/http-backend/organization/org-1/workspace/ws-1/state\""));
        assertTrue(hcl.contains("lock_address   = \"" + apiUrl + "/tfstate/v1/http-backend/organization/org-1/workspace/ws-1/lock\""));
        assertTrue(hcl.contains("unlock_address = \"" + apiUrl + "/tfstate/v1/http-backend/organization/org-1/workspace/ws-1/lock\""));
        assertTrue(hcl.contains("lock_method    = \"POST\""));
        assertTrue(hcl.contains("unlock_method  = \"DELETE\""));
        assertTrue(hcl.contains("password       = \"jwt-token\""));
    }

    @Test
    void saveTerraformPlanReturnsNullWhenLocalFileMissing(@TempDir Path tempDir) {
        ApiTerraformStateImpl subject = newSubject(true);

        String result = subject.saveTerraformPlan("org-1", "ws-1", "job-1", "step-1", tempDir.toFile());

        assertNull(result);
    }

    @Test
    void saveTerraformPlanUploadsWithIntegrityAndReadBack(@TempDir Path tempDir) throws IOException {
        byte[] plan = new byte[]{1, 2, 3, 4, 5};
        File planFile = new File(tempDir.toFile(), "terraformLibrary.tfPlan");
        FileUtils.writeByteArrayToFile(planFile, plan);

        server.createContext("/tfstate/v1/organization/org-1/workspace/ws-1/jobId/job-1/step/step-1/terraform.tfstate",
                echoingStorage());

        ApiTerraformStateImpl subject = newSubject(true);
        String url = subject.saveTerraformPlan("org-1", "ws-1", "job-1", "step-1", tempDir.toFile());

        assertTrue(url.endsWith("/tfstate/v1/organization/org-1/workspace/ws-1/jobId/job-1/step/step-1/terraform.tfstate"));
        byte[] stored = storedBodies.get("/tfstate/v1/organization/org-1/workspace/ws-1/jobId/job-1/step/step-1/terraform.tfstate");
        assertNotNull(stored);
        assertEquals(sha256Hex(plan), sha256Hex(stored));
    }

    @Test
    void saveTerraformPlanThrowsAfterRetriesOnPersistentServerErrors(@TempDir Path tempDir) throws IOException {
        byte[] plan = new byte[]{1, 2, 3, 4, 5};
        FileUtils.writeByteArrayToFile(new File(tempDir.toFile(), "terraformLibrary.tfPlan"), plan);

        AtomicInteger calls = new AtomicInteger();
        server.createContext("/tfstate/v1/organization/org-1/workspace/ws-1/jobId/job-1/step/step-1/terraform.tfstate",
                exchange -> {
                    calls.incrementAndGet();
                    respond(exchange, 500, "boom");
                });

        ApiTerraformStateImpl subject = newSubject(false);

        StateUploadFailedException ex = assertThrows(StateUploadFailedException.class,
                () -> subject.saveTerraformPlan("org-1", "ws-1", "job-1", "step-1", tempDir.toFile()));
        assertTrue(ex.getMessage().contains("HTTP 500"));
        assertTrue(ex.getMessage().contains("Workspace state was NOT modified"));
        assertEquals(3, calls.get(), "expected MAX_UPLOAD_ATTEMPTS=3 PUTs");
    }

    @Test
    void saveTerraformPlanThrowsOn409WithMismatchReason(@TempDir Path tempDir) throws IOException {
        byte[] plan = new byte[]{1, 2, 3};
        FileUtils.writeByteArrayToFile(new File(tempDir.toFile(), "terraformLibrary.tfPlan"), plan);

        server.createContext("/tfstate/v1/organization/org-1/workspace/ws-1/jobId/job-1/step/step-1/terraform.tfstate",
                exchange -> respond(exchange, 409, "{\"error\":\"sha256-mismatch\"}"));

        ApiTerraformStateImpl subject = newSubject(false);

        StateUploadFailedException ex = assertThrows(StateUploadFailedException.class,
                () -> subject.saveTerraformPlan("org-1", "ws-1", "job-1", "step-1", tempDir.toFile()));
        assertTrue(ex.getMessage().contains("sha-256 did not match"));
    }

    @Test
    void saveTerraformPlanThrowsOnReadBackMismatch(@TempDir Path tempDir) throws IOException {
        byte[] plan = new byte[]{9, 9, 9};
        FileUtils.writeByteArrayToFile(new File(tempDir.toFile(), "terraformLibrary.tfPlan"), plan);

        // Accept PUT with 201 but serve different bytes on GET — simulates a faulty
        // or compromised storage backend that committed something other than what
        // was sent. readBack must catch this and fail the upload.
        server.createContext("/tfstate/v1/organization/org-1/workspace/ws-1/jobId/job-1/step/step-1/terraform.tfstate",
                exchange -> {
                    if ("PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                        exchange.getRequestBody().readAllBytes();
                        respond(exchange, 201, "");
                    } else {
                        byte[] tampered = new byte[]{0, 0, 0};
                        exchange.sendResponseHeaders(200, tampered.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(tampered);
                        }
                    }
                });

        ApiTerraformStateImpl subject = newSubject(true);
        StateUploadFailedException ex = assertThrows(StateUploadFailedException.class,
                () -> subject.saveTerraformPlan("org-1", "ws-1", "job-1", "step-1", tempDir.toFile()));
        assertTrue(ex.getMessage().contains("read-back"));
    }

    @Test
    void saveOutputReturnsPathServiceUrlAndUploads() {
        server.createContext("/tfoutput/v1/organization/org-1/job/job-1/step/step-1", echoingStorage());
        when(terraformOutputPathService.getOutputPath("org-1", "job-1", "step-1"))
                .thenReturn("https://example/path/output");

        ApiTerraformStateImpl subject = newSubject(false);
        String result = subject.saveOutput("org-1", "job-1", "step-1", "output-data", "err-data");

        assertEquals("https://example/path/output", result);
        byte[] stored = storedBodies.get("/tfoutput/v1/organization/org-1/job/job-1/step/step-1");
        assertNotNull(stored);
        assertEquals("output-dataerr-data", new String(stored, StandardCharsets.UTF_8));
    }

    @Test
    void saveStateJsonNoOpsWhenApplyJsonIsNull() {
        ApiTerraformStateImpl subject = newSubject(true);
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org-1");
        job.setWorkspaceId("ws-1");
        job.setJobId("job-1");

        subject.saveStateJson(job, null, null);

        verify(terrakubeClient, org.mockito.Mockito.never()).createHistory(any(HistoryRequest.class), any(), any());
    }

    @Test
    void saveStateJsonCreatesHistoryAndUploadsBothRepresentations() {
        List<String> uploadedPaths = new ArrayList<>();
        server.createContext("/tfstate/v1/organization/org-1/workspace/ws-1/history/", exchange -> {
            if ("PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                uploadedPaths.add(exchange.getRequestURI().getPath());
                byte[] body = exchange.getRequestBody().readAllBytes();
                String announced = exchange.getRequestHeaders().getFirst("X-Content-Sha256");
                assertEquals(sha256Hex(body), announced);
                storedBodies.put(exchange.getRequestURI().getPath(), body);
                respond(exchange, 201, "");
            } else if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                byte[] body = storedBodies.get(exchange.getRequestURI().getPath());
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
        });
        when(terraformStatePathService.getStateJsonPath(anyString(), anyString(), anyString()))
                .thenReturn("https://example/state.json");

        ApiTerraformStateImpl subject = newSubject(true);
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org-1");
        job.setWorkspaceId("ws-1");
        job.setJobId("job-1");

        subject.saveStateJson(job, "{\"apply\":1}", "{\"raw\":1}");

        verify(terrakubeClient).createHistory(any(HistoryRequest.class), eq("org-1"), eq("ws-1"));
        assertEquals(2, uploadedPaths.size(), "expected one raw + one json upload");
        assertTrue(uploadedPaths.stream().anyMatch(p -> p.endsWith("/terraform.tfstate")));
        assertTrue(uploadedPaths.stream().anyMatch(p -> p.endsWith("/terraform.json.tfstate")));
    }

    @Test
    void downloadTerraformPlanReturnsFalseWhenJobHasNoPlanUrl(@TempDir Path tempDir) {
        TerrakubeClient deepStubClient = mock(TerrakubeClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(deepStubClient.getJobById("org-1", "job-1").getData().getAttributes().getTerraformPlan())
                .thenReturn(null);

        ApiTerraformStateImpl subject = ApiTerraformStateImpl.builder()
                .terrakubeClient(deepStubClient)
                .terraformStatePathService(terraformStatePathService)
                .terraformOutputPathService(terraformOutputPathService)
                .workspaceSecurity(workspaceSecurity)
                .apiUrl(apiUrl)
                .verifyReadBack(false)
                .build();

        boolean exists = subject.downloadTerraformPlan("org-1", "ws-1", "job-1", "step-1", tempDir.toFile());
        assertFalse(exists);
    }

    @Test
    void downloadTerraformPlanFetchesAndWritesPlan(@TempDir Path tempDir) throws IOException {
        byte[] plan = new byte[]{4, 5, 6};
        server.createContext("/plan", exchange -> {
            exchange.sendResponseHeaders(200, plan.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(plan);
            }
        });

        TerrakubeClient deepStubClient = mock(TerrakubeClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(deepStubClient.getJobById("org-1", "job-1").getData().getAttributes().getTerraformPlan())
                .thenReturn(apiUrl + "/plan");

        ApiTerraformStateImpl subject = ApiTerraformStateImpl.builder()
                .terrakubeClient(deepStubClient)
                .terraformStatePathService(terraformStatePathService)
                .terraformOutputPathService(terraformOutputPathService)
                .workspaceSecurity(workspaceSecurity)
                .apiUrl(apiUrl)
                .verifyReadBack(false)
                .build();

        boolean exists = subject.downloadTerraformPlan("org-1", "ws-1", "job-1", "step-1", tempDir.toFile());
        assertTrue(exists);
        File destination = new File(tempDir.toFile(), "terraformLibrary.tfPlan");
        assertTrue(destination.exists());
        assertEquals(sha256Hex(plan), sha256Hex(FileUtils.readFileToByteArray(destination)));
    }

    @Test
    void checkHeartbeatHealthIsNoOpBeforeStart() {
        ApiTerraformStateImpl subject = newSubject(true);
        // No heartbeat started — should not throw even if hours have "passed".
        subject.checkHeartbeatHealth();
    }

    @Test
    void heartbeatPingsConfiguredEndpointAndUpdatesLastSuccess() throws InterruptedException {
        java.util.concurrent.atomic.AtomicInteger pings = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch firstPing = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/tfstate/v1/http-backend/organization/org-1/workspace/ws-1/lock/heartbeat",
                exchange -> {
                    if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        pings.incrementAndGet();
                        firstPing.countDown();
                        respond(exchange, 200, "");
                    } else {
                        respond(exchange, 405, "");
                    }
                });

        ApiTerraformStateImpl subject = ApiTerraformStateImpl.builder()
                .terrakubeClient(terrakubeClient)
                .terraformStatePathService(terraformStatePathService)
                .terraformOutputPathService(terraformOutputPathService)
                .workspaceSecurity(workspaceSecurity)
                .apiUrl(apiUrl)
                .verifyReadBack(false)
                .heartbeatIntervalSeconds(1L)
                .heartbeatAbortAfterSeconds(60L)
                .build();

        subject.startHeartbeat("org-1", "ws-1");
        try {
            assertTrue(firstPing.await(5, java.util.concurrent.TimeUnit.SECONDS), "expected first heartbeat within 5s");
            assertTrue(pings.get() >= 1);
            // Healthy heartbeat → health check should not throw.
            subject.checkHeartbeatHealth();
        } finally {
            subject.stopHeartbeat();
        }
    }

    @Test
    void checkHeartbeatHealthThrowsWhenAbortThresholdExceeded() {
        // We don't actually start the scheduler — we just flip the running flag
        // and assert the elapsed-time check fires. Simulated by setting a tiny
        // abort threshold and starting the heartbeat against an unreachable URL.
        ApiTerraformStateImpl subject = ApiTerraformStateImpl.builder()
                .terrakubeClient(terrakubeClient)
                .terraformStatePathService(terraformStatePathService)
                .terraformOutputPathService(terraformOutputPathService)
                .workspaceSecurity(workspaceSecurity)
                .apiUrl("http://127.0.0.1:1/unreachable") // black hole
                .verifyReadBack(false)
                .heartbeatIntervalSeconds(60L)
                .heartbeatAbortAfterSeconds(0L) // any elapsed time triggers
                .build();

        subject.startHeartbeat("org-1", "ws-1");
        try {
            // After at least 1 second the elapsed-since-last-success is > 0 > threshold(0).
            try { Thread.sleep(1100); } catch (InterruptedException ignored) {}
            StateUploadFailedException ex = org.junit.jupiter.api.Assertions.assertThrows(
                    StateUploadFailedException.class, subject::checkHeartbeatHealth);
            assertTrue(ex.getMessage().contains("Lost contact with the Terrakube API"));
        } finally {
            subject.stopHeartbeat();
        }
    }

    @Test
    void heartbeatHandlesApi410GoneWithoutUpdatingLastSuccess() throws InterruptedException {
        java.util.concurrent.CountDownLatch first410 = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/tfstate/v1/http-backend/organization/org-1/workspace/ws-1/lock/heartbeat",
                exchange -> {
                    first410.countDown();
                    respond(exchange, 410, "{\"error\":\"lock-lost\"}");
                });

        ApiTerraformStateImpl subject = ApiTerraformStateImpl.builder()
                .terrakubeClient(terrakubeClient)
                .terraformStatePathService(terraformStatePathService)
                .terraformOutputPathService(terraformOutputPathService)
                .workspaceSecurity(workspaceSecurity)
                .apiUrl(apiUrl)
                .verifyReadBack(false)
                .heartbeatIntervalSeconds(1L)
                .heartbeatAbortAfterSeconds(0L)
                .build();

        subject.startHeartbeat("org-1", "ws-1");
        try {
            assertTrue(first410.await(5, java.util.concurrent.TimeUnit.SECONDS));
            // The 410 must NOT count as a successful ping, so the health check trips.
            try { Thread.sleep(1100); } catch (InterruptedException ignored) {}
            org.junit.jupiter.api.Assertions.assertThrows(
                    StateUploadFailedException.class, subject::checkHeartbeatHealth);
        } finally {
            subject.stopHeartbeat();
        }
    }

    @Test
    void stopHeartbeatIsIdempotent() {
        ApiTerraformStateImpl subject = newSubject(false);
        subject.stopHeartbeat();
        subject.stopHeartbeat();
    }

    @Test
    void doubleStartHeartbeatDoesNotLeakAScheduler() {
        ApiTerraformStateImpl subject = newSubject(false);
        subject.startHeartbeat("org-1", "ws-1");
        try {
            // Second start is a no-op (logs a warning).
            subject.startHeartbeat("org-1", "ws-1");
        } finally {
            subject.stopHeartbeat();
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
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
