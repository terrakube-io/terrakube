package io.terrakube.executor.service.executor;

import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.mode.online.OnlineModeServiceImpl;
import io.terrakube.executor.service.scripts.ScriptEngineService;
import io.terrakube.executor.service.shutdown.ShutdownServiceImpl;
import io.terrakube.executor.service.status.UpdateJobStatus;
import io.terrakube.executor.service.terraform.TerraformExecutor;
import io.terrakube.executor.service.workspace.SetupWorkspace;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

class AdmissionControlIntegrationTest {

    /**
     * Runs ExecutorJobImpl.createJob synchronously on the shared bounded pool, the same way the
     * real @Async proxy would submit it - so the test can exercise real queue-rejection behavior
     * without bootstrapping a Spring context (this module has no precedent for @SpringBootTest).
     */
    private static class SubmittingExecutorJob implements ExecutorJob {
        private final ExecutorJobImpl delegate;
        private final ThreadPoolTaskExecutor pool;

        SubmittingExecutorJob(ExecutorJobImpl delegate, ThreadPoolTaskExecutor pool) {
            this.delegate = delegate;
            this.pool = pool;
        }

        @Override
        public void createJob(TerraformJob terraformJob) {
            pool.submit(() -> delegate.createJob(terraformJob));
        }
    }

    @Test
    void onlyOneOfTwoConcurrentJobsIsAcceptedAndThePodAcceptsAThirdOnceTheFirstCompletes() throws Exception {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(0);
        pool.initialize();

        SetupWorkspace setupWorkspace = Mockito.mock(SetupWorkspace.class);
        TerraformExecutor terraformExecutor = Mockito.mock(TerraformExecutor.class);
        UpdateJobStatus updateJobStatus = Mockito.mock(UpdateJobStatus.class);
        ShutdownServiceImpl shutdownService = Mockito.mock(ShutdownServiceImpl.class);
        ScriptEngineService scriptEngineService = Mockito.mock(ScriptEngineService.class);
        JobExecutionWatchdog jobExecutionWatchdog = Mockito.mock(JobExecutionWatchdog.class);
        ApplicationEventPublisher eventPublisher = event -> { };

        CountDownLatch releaseFirstJob = new CountDownLatch(1);
        when(setupWorkspace.prepareWorkspace(any())).thenAnswer(invocation -> {
            releaseFirstJob.await(5, TimeUnit.SECONDS);
            return java.nio.file.Files.createTempDirectory("admission-test").toFile();
        });
        ExecutorJobResult result = new ExecutorJobResult();
        result.setSuccessfulExecution(true);
        when(terraformExecutor.plan(any(), any(), anyBoolean())).thenReturn(result);

        ExecutorCapacityGate gate = new ExecutorCapacityGate();
        RedisTemplate<String, Object> redisTemplate = Mockito.mock(RedisTemplate.class);
        ExecutorJobImpl executorJobImpl = new ExecutorJobImpl(setupWorkspace, terraformExecutor, updateJobStatus,
                new ExecutorFlagsProperties(), shutdownService, scriptEngineService, eventPublisher,
                jobExecutionWatchdog, gate, redisTemplate);
        OnlineModeServiceImpl controller = new OnlineModeServiceImpl(
                new SubmittingExecutorJob(executorJobImpl, pool), gate, eventPublisher);

        TerraformJob firstJob = new TerraformJob();
        firstJob.setOrganizationId("org");
        firstJob.setWorkspaceId("workspace-1");
        firstJob.setType("terraformPlan");
        firstJob.setBranch("remote-content");
        firstJob.setEnvironmentVariables(new java.util.HashMap<>());
        firstJob.setVariables(new java.util.HashMap<>());

        TerraformJob secondJob = new TerraformJob();
        secondJob.setOrganizationId("org");
        secondJob.setWorkspaceId("workspace-2");
        secondJob.setType("terraformPlan");

        // First request: pod is idle, gate is acquired, job starts running (blocked on the latch).
        ResponseEntity<TerraformJob> firstResponse = controller.terraformJob(firstJob);
        assertEquals(HttpStatus.ACCEPTED, firstResponse.getStatusCode());

        // Second request while the first is still running: gate is already held -> 503, no
        // submission to the pool at all (proves no local queueing).
        ResponseEntity<TerraformJob> secondResponse = controller.terraformJob(secondJob);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, secondResponse.getStatusCode());

        // Let the first job finish, which releases the gate.
        releaseFirstJob.countDown();
        long deadline = System.currentTimeMillis() + 5000;
        while (gate.tryAcquire() == false && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        gate.release();

        // Third request after completion: pod accepts again.
        TerraformJob thirdJob = new TerraformJob();
        thirdJob.setOrganizationId("org");
        thirdJob.setWorkspaceId("workspace-3");
        thirdJob.setType("terraformPlan");
        thirdJob.setBranch("remote-content");
        thirdJob.setEnvironmentVariables(new java.util.HashMap<>());
        thirdJob.setVariables(new java.util.HashMap<>());
        ResponseEntity<TerraformJob> thirdResponse = controller.terraformJob(thirdJob);
        assertEquals(HttpStatus.ACCEPTED, thirdResponse.getStatusCode());

        pool.shutdown();
    }
}
