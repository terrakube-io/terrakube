package io.terrakube.api.plugin.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.internal.OutOfDirectMemoryError;
import io.netty.util.internal.PlatformDependent;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.terrakube.api.plugin.scheduler.job.tcl.executor.persistent.PersistentExecutorService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import reactor.core.publisher.Flux;
import reactor.netty.ReactorNetty;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsConnectionType;
import io.terrakube.api.rs.vcs.VcsStatus;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.workspace.Workspace;

/**
 * End-to-end case-testing suite for issue #3956 against a real PostgreSQL instance and a real
 * loopback TCP {@link ServerSocket} acting as the persistent executor.
 *
 * <p><b>Why this suite is structured this way:</b>
 * <ul>
 *   <li><b>No {@code @Transactional}:</b> {@link ScheduleJob#execute} holds no surrounding
 *       transaction across {@code POST /api/v1/terraform-rs}. Annotating tests with
 *       {@code @Transactional} hides detached-entity {@code merge()} overwrites and lazy-proxy
 *       {@code LazyInitializationException}s that only appear across real per-repository
 *       transaction boundaries.</li>
 *   <li><b>Raw TCP {@code ServerSocket} instead of mocked {@code WebClient} / WireMock:</b>
 *       {@code Mono.block} vs Netty {@code ReadTimeoutHandler}, mid-chunk {@code TCP RST} during
 *       {@code .toBodilessEntity()} body drain, and Netty {@code AdaptivePoolingAllocator}
 *       {@code OutOfDirectMemoryError} all occur inside Reactor Netty's real NIO channel pipeline.</li>
 * </ul>
 *
 * <p><b>Covered failure boundaries:</b>
 * <ol>
 *   <li>Silent {@code 202} read timeout after executor completed the step while holding
 *       {@code ExecutionLease} (default {@code 500ms} fast-forward, or full {@code 60s} clock via
 *       {@code -Dterrakube.test.realDispatchTimeout=true} / {@code TERRAKUBE_TEST_REAL_DISPATCH_TIMEOUT=true}).</li>
 *   <li>Late non-retryable {@code 500} after step completion exercising the {@code failDispatched} SQL CAS.</li>
 *   <li>Two-stage {@code 202} mid-body {@code TCP RST} during {@code git clone} ({@code step = pending})
 *       followed by step completion, guarding against transient {@code failed} VCS status and
 *       permanent {@code step.name} corruption masked by subsequent auto-reconciliation.</li>
 *   <li>Paketo {@code 10 MiB} Netty {@code OutOfDirectMemoryError} while draining a {@code 202} body.</li>
 *   <li>Genuine executor {@code 500} while the target step is still {@code pending} (regression guard).</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class LostAcknowledgementDispatchRaceIntegrationTest {

    private static final long PAKETO_DEFAULT_MAX_DIRECT_MEMORY_BYTES = 10L * 1024 * 1024; // 10485760

    static {
        // Reproduce Paketo Buildpacks' default 10 MiB Netty direct-memory cap in isolated counter mode
        // (-Dio.netty.maxDirectMemory=10485760) without restricting heap or JDBC NIO buffers.
        if (System.getProperty("io.netty.maxDirectMemory") == null) {
            System.setProperty("io.netty.maxDirectMemory", Long.toString(PAKETO_DEFAULT_MAX_DIRECT_MEMORY_BYTES));
        }
    }

    private static final boolean REAL_DISPATCH_TIMEOUT = Boolean.getBoolean("terrakube.test.realDispatchTimeout")
            || Boolean.parseBoolean(System.getenv("TERRAKUBE_TEST_REAL_DISPATCH_TIMEOUT"));
    private static final Duration DISPATCH_RESPONSE_TIMEOUT = REAL_DISPATCH_TIMEOUT
            ? Duration.ofSeconds(60)
            : Duration.ofMillis(500);

    @FunctionalInterface
    private interface SocketResponder {
        void handle(Socket client) throws Exception;
    }

    private static final ServerSocket FAKE_EXECUTOR_SERVER = openServerSocket();
    private static final AtomicReference<SocketResponder> NEXT_RESPONDER = new AtomicReference<>();
    private static final Thread ACCEPT_LOOP = startAcceptLoop();

    private static final String TWO_STEP_TCL = Base64.getEncoder().encodeToString(("""
            flow:
              - type: "terraformApply"
                step: 300
                name: "Terraform Apply"
              - type: "customScripts"
                step: 400
                name: "Custom Scripts"
                commands:
                  - runtime: "BASH"
                    priority: 100
                    before: false
                    after: false
                    script: "echo post-apply"
            """).getBytes(StandardCharsets.UTF_8));

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("terrakube")
            .withUsername("terrakube")
            .withPassword("terrakube");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("io.terrakube.api.plugin.datasource.type", () -> "POSTGRESQL");
        registry.add("io.terrakube.api.plugin.datasource.hostname", postgreSQLContainer::getHost);
        registry.add("io.terrakube.api.plugin.datasource.databasePort", () -> postgreSQLContainer.getMappedPort(5432).toString());
        registry.add("io.terrakube.api.plugin.datasource.databaseName", postgreSQLContainer::getDatabaseName);
        registry.add("io.terrakube.api.plugin.datasource.databaseUser", postgreSQLContainer::getUsername);
        registry.add("io.terrakube.api.plugin.datasource.databasePassword", postgreSQLContainer::getPassword);
        registry.add("io.terrakube.api.plugin.scheduler.instanceName", () -> "lostAckDispatchRaceIT");
        registry.add("io.terrakube.executor.url",
                () -> "http://127.0.0.1:" + FAKE_EXECUTOR_SERVER.getLocalPort() + "/api/v1/terraform-rs");
    }

    @AfterAll
    static void closeServer() throws IOException {
        FAKE_EXECUTOR_SERVER.close();
        ACCEPT_LOOP.interrupt();
    }

    private static ServerSocket openServerSocket() {
        try {
            return new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to bind fake executor socket", e);
        }
    }

    private static Thread startAcceptLoop() {
        Thread thread = new Thread(() -> {
            while (!FAKE_EXECUTOR_SERVER.isClosed()) {
                try {
                    Socket client = FAKE_EXECUTOR_SERVER.accept();
                    SocketResponder responder = NEXT_RESPONDER.getAndSet(null);
                    Thread handler = new Thread(() -> {
                        try (Socket c = client) {
                            drainHttpRequest(c.getInputStream());
                            if (responder != null) {
                                responder.handle(c);
                            }
                        } catch (Exception ignored) {
                        }
                    }, "fake-executor-conn");
                    handler.setDaemon(true);
                    handler.start();
                } catch (Exception ignored) {
                }
            }
        }, "fake-executor-accept-loop");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void drainHttpRequest(InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        int headerEnd = -1;
        int contentLength = 0;
        StringBuilder raw = new StringBuilder();
        while (headerEnd < 0) {
            int read = in.read(buffer);
            if (read <= 0) {
                return;
            }
            raw.append(new String(buffer, 0, read, StandardCharsets.ISO_8859_1));
            headerEnd = raw.indexOf("\r\n\r\n");
        }
        String headers = raw.substring(0, headerEnd);
        for (String line : headers.split("\r\n")) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        int bodyAlreadyRead = raw.length() - (headerEnd + 4);
        int remaining = contentLength - bodyAlreadyRead;
        while (remaining > 0) {
            int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read <= 0) {
                break;
            }
            remaining -= read;
        }
    }

    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    @MockitoBean
    private RedisMessageListenerContainer redisMessageListenerContainer;

    @MockitoBean
    private GitLabWebhookService gitLabWebhookService;

    @Autowired
    private ScheduleJob scheduleJob;
    @Autowired
    private PersistentExecutorService persistentExecutorService;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private StepRepository stepRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private VcsRepository vcsRepository;

    private final Map<String, Object> redisStore = new ConcurrentHashMap<>();
    private final AtomicBoolean oomFilterInstalled = new AtomicBoolean(false);
    private final AtomicBoolean simulate202BodyDirectOom = new AtomicBoolean(false);
    private Integer createdJobId;

    private static OutOfDirectMemoryError newPaketoDirectMemoryError() {
        try {
            Constructor<OutOfDirectMemoryError> ctor = OutOfDirectMemoryError.class.getDeclaredConstructor(String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(
                    "failed to allocate 262144 byte(s) of direct memory (used: 10428423, max: 10485760)");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to instantiate OutOfDirectMemoryError", e);
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUpRedisLeaseSimulation() {
        redisStore.clear();
        NEXT_RESPONDER.set(null);
        simulate202BodyDirectOom.set(false);
        ReflectionTestUtils.setField(persistentExecutorService, "responseTimeout", DISPATCH_RESPONSE_TIMEOUT);
        if (oomFilterInstalled.compareAndSet(false, true)) {
            WebClient.Builder builder = (WebClient.Builder) ReflectionTestUtils.getField(
                    persistentExecutorService, "webClientBuilder");
            if (builder != null) {
                builder.filter((request, next) -> next.exchange(request).map(response -> {
                    if (simulate202BodyDirectOom.compareAndSet(true, false)) {
                        return response.mutate()
                                .body(flux -> flux.thenMany(
                                        Flux.error(ReactorNetty.wrapException(newPaketoDirectMemoryError()))))
                                .build();
                    }
                    return response;
                }));
            }
        }

        ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), any(), any(Duration.class)))
                .thenAnswer(inv -> redisStore.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenAnswer(inv -> {
                    RedisScript<?> script = inv.getArgument(0);
                    List<String> keys = inv.getArgument(1);
                    Object token = inv.getArgument(2);
                    String lua = script.getScriptAsString();
                    if (keys.size() == 2 && lua.contains("'NX'")) {
                        if (!Objects.equals(redisStore.get(keys.get(0)), token)) {
                            return 0L;
                        }
                        return redisStore.putIfAbsent(keys.get(1), token) == null ? 1L : 0L;
                    }
                    if (lua.contains("'del'")) {
                        return redisStore.remove(keys.get(0), token) ? 1L : 0L;
                    }
                    if (lua.contains("'pexpire'")) {
                        return Objects.equals(redisStore.get(keys.get(0)), token) ? 1L : 0L;
                    }
                    return 0L;
                });
    }

    @AfterEach
    void cleanUpJob() {
        if (createdJobId != null) {
            stepRepository.deleteAll(stepRepository.findByJobId(createdJobId));
            jobRepository.findById(createdJobId).ifPresent(jobRepository::delete);
            createdJobId = null;
        }
    }

    private record SeededRun(int jobId, UUID step400Id) {
    }

    private SeededRun seedPendingStep400AfterCompletedApply() {
        Organization org = new Organization();
        org.setName("org-" + UUID.randomUUID().toString().substring(0, 8));
        org = organizationRepository.save(org);

        Vcs vcs = new Vcs();
        vcs.setName("vcs-" + UUID.randomUUID().toString().substring(0, 8));
        vcs.setDescription("GitLab test VCS");
        vcs.setVcsType(VcsType.GITLAB);
        vcs.setConnectionType(VcsConnectionType.STANDALONE);
        vcs.setEndpoint("https://gitlab.example.com");
        vcs.setApiUrl("https://gitlab.example.com/api/v4");
        vcs.setAccessToken("glpat-test-token");
        vcs.setStatus(VcsStatus.COMPLETED);
        vcs.setOrganization(org);
        vcs = vcsRepository.save(vcs);

        Workspace workspace = new Workspace();
        workspace.setName("ws-" + UUID.randomUUID().toString().substring(0, 8));
        workspace.setSource("https://gitlab.example.com/group/project.git");
        workspace.setBranch("feat/test");
        workspace.setTerraformVersion("1.6.0");
        workspace.setOrganization(org);
        workspace.setVcs(vcs);
        workspace = workspaceRepository.save(workspace);

        Job job = new Job();
        job.setOrganization(org);
        job.setWorkspace(workspace);
        job.setStatus(JobStatus.pending);
        job.setPlanChanges(true);
        job.setVia("Gitlab");
        job.setCommitId("9ea4ccc3194de2a52bcee16ce08a164448eec8d9");
        job.setOutput("Step 300 completed\n");
        job.setTcl(TWO_STEP_TCL);
        job = jobRepository.save(job);
        createdJobId = job.getId();

        Step step300 = new Step();
        step300.setJob(job);
        step300.setStepNumber(300);
        step300.setName("Terraform Apply");
        step300.setStatus(JobStatus.completed);
        stepRepository.save(step300);

        Step step400 = new Step();
        step400.setJob(job);
        step400.setStepNumber(400);
        step400.setName("Custom Scripts");
        step400.setStatus(JobStatus.pending);
        step400 = stepRepository.save(step400);

        return new SeededRun(job.getId(), step400.getId());
    }

    private void simulateExecutorCompletingStepInDatabase(int jobId, UUID step400Id) {
        Step liveStep = stepRepository.findById(step400Id).orElseThrow();
        liveStep.setStatus(JobStatus.completed);
        liveStep.setOutput("https://localhost:8080/tfoutput/v1/job/" + jobId + "/step/" + step400Id);
        stepRepository.save(liveStep);

        Job liveJob = jobRepository.findById(jobId).orElseThrow();
        liveJob.setOutput("Step 300 completed\nStep 400 completed\n");
        liveJob.setStatus(JobStatus.pending);
        jobRepository.save(liveJob);
    }

    @Test
    void silent202ReadTimeoutAfterExecutorCompletedStepReconcilesJobAndRetainsDispatchClaim() {
        SeededRun seeded = seedPendingStep400AfterCompletedApply();
        // Materialize the pre-dispatch snapshot exactly as ScheduleJob.execute() does before HTTP I/O.
        Job detachedBeforeDispatch = jobRepository.findById(seeded.jobId()).orElseThrow();

        NEXT_RESPONDER.set(client -> {
            // Exact Job 62 reproduction: executor accepts and completes Step 400 in PostgreSQL while
            // the HTTP 202 response never arrives on the open TCP socket, holding silent past
            // DISPATCH_RESPONSE_TIMEOUT (500ms default fast-forward, or 60s when
            // -Dterrakube.test.realDispatchTimeout=true) so Netty's real ReadTimeoutHandler fires.
            simulateExecutorCompletingStepInDatabase(seeded.jobId(), seeded.step400Id());
            Thread.sleep(DISPATCH_RESPONSE_TIMEOUT.plusMillis(1500).toMillis());
        });

        boolean deschedule = scheduleJob.runExecution(detachedBeforeDispatch);

        Job persistedJob = jobRepository.findById(seeded.jobId()).orElseThrow();
        Step persistedStep400 = stepRepository.findById(seeded.step400Id()).orElseThrow();

        assertThat(deschedule).isTrue();
        assertThat(persistedJob.getStatus()).isEqualTo(JobStatus.completed);
        assertThat(persistedJob.getOutput()).isEqualTo("Step 300 completed\nStep 400 completed\n");
        assertThat(persistedStep400.getStatus()).isEqualTo(JobStatus.completed);
        assertThat(persistedStep400.getName()).isEqualTo("Custom Scripts");
        assertThat(redisStore).containsKey(ExecutionLease.claimKey(seeded.jobId(), seeded.step400Id().toString()));
        verify(gitLabWebhookService).sendCommitStatus(any(Job.class), eq(JobStatus.completed), any());
        verify(gitLabWebhookService, never()).sendCommitStatus(any(Job.class), eq(JobStatus.unknown), any());
        verify(gitLabWebhookService, never()).sendCommitStatus(any(Job.class), eq(JobStatus.failed), any());
    }

    @Test
    void lateExecutionErrorAfterExecutorCompletedStepDoesNotOverwriteCompletedRunToFailed() {
        SeededRun seeded = seedPendingStep400AfterCompletedApply();
        Job detachedBeforeDispatch = jobRepository.findById(seeded.jobId()).orElseThrow();

        NEXT_RESPONDER.set(client -> {
            // Exercises the atomic failDispatched SQL CAS directly: even if a non-retryable upstream
            // error reaches errorJobAtStep after the executor already finished Step 400, the stale
            // detached Job snapshot must never clobber the completed run.
            simulateExecutorCompletingStepInDatabase(seeded.jobId(), seeded.step400Id());
            OutputStream out = client.getOutputStream();
            out.write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
        });

        boolean deschedule = scheduleJob.runExecution(detachedBeforeDispatch);

        Job persistedJob = jobRepository.findById(seeded.jobId()).orElseThrow();
        Step persistedStep400 = stepRepository.findById(seeded.step400Id()).orElseThrow();

        assertThat(deschedule).isTrue();
        assertThat(persistedJob.getStatus()).isEqualTo(JobStatus.completed);
        assertThat(persistedJob.getOutput()).isEqualTo("Step 300 completed\nStep 400 completed\n");
        assertThat(persistedStep400.getStatus()).isEqualTo(JobStatus.completed);
        assertThat(persistedStep400.getName()).isEqualTo("Custom Scripts");
        verify(gitLabWebhookService).sendCommitStatus(any(Job.class), eq(JobStatus.completed), any());
        verify(gitLabWebhookService, never()).sendCommitStatus(any(Job.class), eq(JobStatus.unknown), any());
    }

    @Test
    void truncated202BodyDuringGitCloneNeverFlashesFailedOrCorruptsStepNameWhenStepLaterAutoHealsToCompleted() {
        SeededRun seeded = seedPendingStep400AfterCompletedApply();
        Job detachedBeforeDispatch = jobRepository.findById(seeded.jobId()).orElseThrow();

        NEXT_RESPONDER.set(client -> {
            // Stage 1 (t ~ 0.2s): Executor returns HTTP 202 Accepted headers while still cloning the
            // repo (step 400 remains pending in PostgreSQL), and the chunked response body aborts.
            OutputStream out = client.getOutputStream();
            out.write(("HTTP/1.1 202 Accepted\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Transfer-Encoding: chunked\r\n\r\n"
                    + "10\r\n{\"jobId\":\"" + seeded.jobId() + "\"")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            client.setSoLinger(true, 0);
        });

        boolean dispatchDeschedule = scheduleJob.runExecution(detachedBeforeDispatch);
        Job afterDispatchJob = jobRepository.findById(seeded.jobId()).orElseThrow();
        Step afterDispatchStep400 = stepRepository.findById(seeded.step400Id()).orElseThrow();
        boolean claimHeldAfterDispatch = redisStore.containsKey(ExecutionLease.claimKey(seeded.jobId(), seeded.step400Id().toString()));

        // Stage 2 (t ~ 15s): Because the executor actually accepted the job on 202, it finishes Step 400
        // in the background, updates step=completed and job=pending, and JobManageHook runs ScheduleJob.
        // On unfixed main, Stage 2 "auto-healed" job.status from failed -> completed, but left step.name
        // permanently corrupted ("Error sending to executor: ... 202 Accepted from POST ...") and had
        // already sent a false failed commit status to GitLab in Stage 1.
        simulateExecutorCompletingStepInDatabase(seeded.jobId(), seeded.step400Id());
        boolean finalDeschedule = scheduleJob.runExecution(jobRepository.findById(seeded.jobId()).orElseThrow());

        Job finalJob = jobRepository.findById(seeded.jobId()).orElseThrow();
        Step finalStep400 = stepRepository.findById(seeded.step400Id()).orElseThrow();
        long vcsFailureCalls = mockingDetails(gitLabWebhookService).getInvocations().stream()
                .filter(inv -> "sendCommitStatus".equals(inv.getMethod().getName()))
                .filter(inv -> inv.getArgument(1) == JobStatus.unknown || inv.getArgument(1) == JobStatus.failed)
                .count();
        long vcsCompletedCalls = mockingDetails(gitLabWebhookService).getInvocations().stream()
                .filter(inv -> "sendCommitStatus".equals(inv.getMethod().getName()))
                .filter(inv -> inv.getArgument(1) == JobStatus.completed)
                .count();

        assertSoftly(softly -> {
            softly.assertThat(dispatchDeschedule).as("Stage 1 deschedule").isTrue();
            softly.assertThat(afterDispatchJob.getStatus()).as("Stage 1 job.status after truncated 202").isEqualTo(JobStatus.queue);
            softly.assertThat(afterDispatchStep400.getStatus()).as("Stage 1 step.status during git clone").isEqualTo(JobStatus.pending);
            softly.assertThat(claimHeldAfterDispatch).as("Stage 1 dispatchClaim retained").isTrue();
            softly.assertThat(finalDeschedule).as("Stage 2 final deschedule").isTrue();
            softly.assertThat(finalJob.getStatus()).as("Stage 2 final job.status").isEqualTo(JobStatus.completed);
            softly.assertThat(finalJob.getOutput()).as("Stage 2 final job.output").isEqualTo("Step 300 completed\nStep 400 completed\n");
            softly.assertThat(finalStep400.getStatus()).as("Stage 2 final step.status").isEqualTo(JobStatus.completed);
            softly.assertThat(finalStep400.getName()).as("Stage 2 final step.name not corrupted").isEqualTo("Custom Scripts");
            softly.assertThat(vcsFailureCalls).as("False failed VCS statuses sent").isZero();
            softly.assertThat(vcsCompletedCalls).as("Completed VCS statuses sent").isEqualTo(1L);
        });
    }

    @Test
    void directMemoryExhaustionWhileDraining202BodyReproducesExactRun71ErrorAndAutoHealSequence() throws Exception {
        // When this test class loads first (standalone run or -Dio.netty.maxDirectMemory=10485760),
        // exercise Netty's real 10 MiB direct-buffer allocator pin; when an earlier test class in a
        // multi-class Surefire JVM already initialized PlatformDependent with heap-sized maxDirectMemory,
        // fall back to injecting the exact ReactorNetty-wrapped OutOfDirectMemoryError on body drain.
        boolean isolated10MiBActive = PlatformDependent.maxDirectMemory() == PAKETO_DEFAULT_MAX_DIRECT_MEMORY_BYTES;
        if (!isolated10MiBActive) {
            simulate202BodyDirectOom.set(true);
        }

        SeededRun seeded = seedPendingStep400AfterCompletedApply();
        Job detachedBeforeDispatch = jobRepository.findById(seeded.jobId()).orElseThrow();

        List<ByteBuf> pinnedDirectBuffers = new ArrayList<>();
        AtomicReference<Socket> activeClient = new AtomicReference<>();
        CountDownLatch dispatchFinished = new CountDownLatch(1);
        CountDownLatch responderReleased = new CountDownLatch(1);

        NEXT_RESPONDER.set(client -> {
            activeClient.set(client);
            try {
                OutputStream out = client.getOutputStream();
                int bodyLen = isolated10MiBActive ? 2 * 1024 * 1024 : 64;
                // 1. Send HTTP 202 Accepted status + headers first so WebClient parses 202 on its
                //    existing small header buffer while Step 400 is still pending (git clone).
                out.write(("HTTP/1.1 202 Accepted\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + bodyLen + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                if (!isolated10MiBActive) {
                    byte[] smallBody = new byte[bodyLen];
                    Arrays.fill(smallBody, (byte) 'x');
                    out.write(smallBody);
                    out.flush();
                    return;
                }
                Thread.sleep(50);

                // 2. Pin Netty's direct memory counter to 10,428,423 / 10,485,760 bytes (leaving
                //    57,337 bytes headroom — matching Run #71's exact Paketo 10 MiB pressure) right
                //    before streaming the large echoed TerraformJob JSON body.
                long freeBytes = PlatformDependent.maxDirectMemory() - PlatformDependent.usedDirectMemory();
                long reserve = freeBytes - 57_337L;
                if (reserve > 0) {
                    synchronized (pinnedDirectBuffers) {
                        pinnedDirectBuffers.add(UnpooledByteBufAllocator.DEFAULT.directBuffer((int) reserve));
                    }
                }

                // 3. Stream the echoed JSON body in 64 KiB bursts so AdaptiveRecvByteBufAllocator
                //    ramps up and AdaptivePoolingAllocator attempts a 262,144-byte direct chunk,
                //    throwing genuine io.netty.util.internal.OutOfDirectMemoryError.
                byte[] chunk = new byte[65536];
                Arrays.fill(chunk, (byte) 'x');
                int remaining = bodyLen;
                while (remaining > 0) {
                    int writeLen = Math.min(chunk.length, remaining);
                    out.write(chunk, 0, writeLen);
                    out.flush();
                    remaining -= writeLen;
                }
            } finally {
                dispatchFinished.await(5, TimeUnit.SECONDS);
                synchronized (pinnedDirectBuffers) {
                    for (ByteBuf buf : pinnedDirectBuffers) {
                        buf.release();
                    }
                    pinnedDirectBuffers.clear();
                }
                responderReleased.countDown();
            }
        });

        boolean dispatchDeschedule;
        try {
            dispatchDeschedule = scheduleJob.runExecution(detachedBeforeDispatch);
        } finally {
            dispatchFinished.countDown();
            Socket socket = activeClient.get();
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
            responderReleased.await(5, TimeUnit.SECONDS);
            synchronized (pinnedDirectBuffers) {
                for (ByteBuf buf : pinnedDirectBuffers) {
                    buf.release();
                }
                pinnedDirectBuffers.clear();
            }
        }

        Job afterDispatchJob = jobRepository.findById(seeded.jobId()).orElseThrow();
        Step afterDispatchStep400 = stepRepository.findById(seeded.step400Id()).orElseThrow();
        boolean claimHeldAfterDispatch = redisStore.containsKey(ExecutionLease.claimKey(seeded.jobId(), seeded.step400Id().toString()));

        // Stage 2: Executor finishes Step 400 and triggers ScheduleJob reconciliation (auto-heal).
        simulateExecutorCompletingStepInDatabase(seeded.jobId(), seeded.step400Id());
        boolean finalDeschedule = scheduleJob.runExecution(jobRepository.findById(seeded.jobId()).orElseThrow());

        Job finalJob = jobRepository.findById(seeded.jobId()).orElseThrow();
        Step finalStep400 = stepRepository.findById(seeded.step400Id()).orElseThrow();
        long vcsFailureCalls = mockingDetails(gitLabWebhookService).getInvocations().stream()
                .filter(inv -> "sendCommitStatus".equals(inv.getMethod().getName()))
                .filter(inv -> inv.getArgument(1) == JobStatus.unknown || inv.getArgument(1) == JobStatus.failed)
                .count();
        long vcsCompletedCalls = mockingDetails(gitLabWebhookService).getInvocations().stream()
                .filter(inv -> "sendCommitStatus".equals(inv.getMethod().getName()))
                .filter(inv -> inv.getArgument(1) == JobStatus.completed)
                .count();

        boolean finalDispatchDeschedule = dispatchDeschedule;
        assertSoftly(softly -> {
            softly.assertThat(finalDispatchDeschedule).as("Stage 1 deschedule").isTrue();
            softly.assertThat(afterDispatchJob.getStatus()).as("Stage 1 job.status after 202 OOM").isEqualTo(JobStatus.queue);
            softly.assertThat(afterDispatchStep400.getStatus()).as("Stage 1 step.status during git clone").isEqualTo(JobStatus.pending);
            softly.assertThat(claimHeldAfterDispatch).as("Stage 1 dispatchClaim retained").isTrue();
            softly.assertThat(finalDeschedule).as("Stage 2 final deschedule").isTrue();
            softly.assertThat(finalJob.getStatus()).as("Stage 2 final job.status").isEqualTo(JobStatus.completed);
            softly.assertThat(finalJob.getOutput()).as("Stage 2 final job.output").isEqualTo("Step 300 completed\nStep 400 completed\n");
            softly.assertThat(finalStep400.getStatus()).as("Stage 2 final step.status").isEqualTo(JobStatus.completed);
            softly.assertThat(finalStep400.getName()).as("Stage 2 final step.name not corrupted").isEqualTo("Custom Scripts");
            softly.assertThat(vcsFailureCalls).as("False failed VCS statuses sent").isZero();
            softly.assertThat(vcsCompletedCalls).as("Completed VCS statuses sent").isEqualTo(1L);
        });
    }

    @Test
    void genuineExecutor500WhileStepStillPendingFailsJobAndStepCleanly() {
        SeededRun seeded = seedPendingStep400AfterCompletedApply();
        Job detachedBeforeDispatch = jobRepository.findById(seeded.jobId()).orElseThrow();

        NEXT_RESPONDER.set(client -> {
            OutputStream out = client.getOutputStream();
            out.write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
        });

        boolean deschedule = scheduleJob.runExecution(detachedBeforeDispatch);

        Job persistedJob = jobRepository.findById(seeded.jobId()).orElseThrow();
        Step persistedStep400 = stepRepository.findById(seeded.step400Id()).orElseThrow();

        assertThat(deschedule).isTrue();
        assertThat(persistedJob.getStatus()).isEqualTo(JobStatus.failed);
        assertThat(persistedStep400.getStatus()).isEqualTo(JobStatus.failed);
        assertThat(persistedStep400.getName()).startsWith("Error sending to executor:");
        verify(gitLabWebhookService).sendCommitStatus(any(Job.class), eq(JobStatus.unknown), any());
    }
}
