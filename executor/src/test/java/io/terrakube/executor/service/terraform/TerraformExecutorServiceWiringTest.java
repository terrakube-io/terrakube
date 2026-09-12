package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.configuration.StructuredOutputProperties;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.executor.ExecutorCapacityGate;
import io.terrakube.executor.service.executor.ExecutorJobImpl;
import io.terrakube.executor.service.executor.JobExecutionWatchdog;
import io.terrakube.executor.service.logs.ProcessLogs;
import io.terrakube.executor.service.opa.OpaExecutorService;
import io.terrakube.executor.service.scripts.ScriptEngineService;
import io.terrakube.executor.service.shutdown.ShutdownServiceImpl;
import io.terrakube.executor.service.status.UpdateJobStatus;
import io.terrakube.executor.service.terraform.structured.StructuredOutputPersistenceQueue;
import io.terrakube.executor.service.workspace.SetupWorkspace;
import io.terrakube.executor.service.workspace.SetupWorkspaceImpl;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import io.terrakube.terraform.TerraformClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class TerraformExecutorServiceWiringTest {

    @Configuration
    static class TestConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
        @Bean StructuredOutputProperties structuredOutputProperties() { return new StructuredOutputProperties(); }
        @Bean ExecutorFlagsProperties executorFlagsProperties() { return new ExecutorFlagsProperties(); }
        @Bean TerraformClient terraformClient() { return Mockito.mock(TerraformClient.class); }
        @Bean TerraformState terraformState() { return Mockito.mock(TerraformState.class); }
        @Bean ScriptEngineService scriptEngineService() { return Mockito.mock(ScriptEngineService.class); }
        @Bean ProcessLogs processLogs() { return Mockito.mock(ProcessLogs.class); }
        @Bean PlanStructuredOutputService planStructuredOutputService() { return Mockito.mock(PlanStructuredOutputService.class); }
        @Bean ApplyStructuredOutputService applyStructuredOutputService() { return Mockito.mock(ApplyStructuredOutputService.class); }
        @Bean TerraformOutputsService terraformOutputsService() { return Mockito.mock(TerraformOutputsService.class); }
        @Bean RedisTemplate redisTemplate() { return Mockito.mock(RedisTemplate.class); }
        @Bean StructuredOutputPersistenceQueue structuredOutputPersistenceQueue() { return Mockito.mock(StructuredOutputPersistenceQueue.class); }
        @Bean OpaExecutorService opaExecutorService() { return Mockito.mock(OpaExecutorService.class); }
        @Bean WorkspaceSecurity workspaceSecurity() { return Mockito.mock(WorkspaceSecurity.class); }
        @Bean TerrakubeClient terrakubeClient() { return Mockito.mock(TerrakubeClient.class); }
        @Bean UpdateJobStatus updateJobStatus() { return Mockito.mock(UpdateJobStatus.class); }
        @Bean ShutdownServiceImpl shutdownService() { return Mockito.mock(ShutdownServiceImpl.class); }
        @Bean JobExecutionWatchdog jobExecutionWatchdog() { return Mockito.mock(JobExecutionWatchdog.class); }
        @Bean ExecutorCapacityGate executorCapacityGate() { return Mockito.mock(ExecutorCapacityGate.class); }
        @Bean ApplicationEventPublisher applicationEventPublisher() { return Mockito.mock(ApplicationEventPublisher.class); }
    }

    @Test
    void verifiesTerraformExecutorAndWorkspaceAndExecutorJobAutowiring() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfiguration.class)
                .withPropertyValues(
                        "io.terrakube.terraform.flags.enableColor=true",
                        "io.terrakube.executor.redis.timeout=30",
                        "io.terrakube.client.enableSecurity=true",
                        "io.terrakube.api.url=https://terrakube-api.platform.local"
                )
                .withBean(TerraformExecutorServiceImpl.class)
                .withBean(SetupWorkspaceImpl.class)
                .withBean(ExecutorJobImpl.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TerraformExecutorServiceImpl.class);
                    assertThat(context).hasSingleBean(SetupWorkspaceImpl.class);
                    assertThat(context).hasSingleBean(ExecutorJobImpl.class);

                    TerraformExecutorServiceImpl executorService = context.getBean(TerraformExecutorServiceImpl.class);
                    assertThat(executorService.opaExecutorService).isNotNull();
                });
    }

    @Configuration
    static class ConfigWithoutOpa {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
        @Bean StructuredOutputProperties structuredOutputProperties() { return new StructuredOutputProperties(); }
        @Bean ExecutorFlagsProperties executorFlagsProperties() { return new ExecutorFlagsProperties(); }
        @Bean TerraformClient terraformClient() { return Mockito.mock(TerraformClient.class); }
        @Bean TerraformState terraformState() { return Mockito.mock(TerraformState.class); }
        @Bean ScriptEngineService scriptEngineService() { return Mockito.mock(ScriptEngineService.class); }
        @Bean ProcessLogs processLogs() { return Mockito.mock(ProcessLogs.class); }
        @Bean PlanStructuredOutputService planStructuredOutputService() { return Mockito.mock(PlanStructuredOutputService.class); }
        @Bean ApplyStructuredOutputService applyStructuredOutputService() { return Mockito.mock(ApplyStructuredOutputService.class); }
        @Bean TerraformOutputsService terraformOutputsService() { return Mockito.mock(TerraformOutputsService.class); }
        @Bean RedisTemplate redisTemplate() { return Mockito.mock(RedisTemplate.class); }
        @Bean StructuredOutputPersistenceQueue structuredOutputPersistenceQueue() { return Mockito.mock(StructuredOutputPersistenceQueue.class); }
    }

    @Test
    void verifiesAutowiringWithoutOptionalOpaExecutorService() {
        new ApplicationContextRunner()
                .withUserConfiguration(ConfigWithoutOpa.class)
                .withPropertyValues(
                        "io.terrakube.terraform.flags.enableColor=false",
                        "io.terrakube.executor.redis.timeout=30"
                )
                .withBean(TerraformExecutorServiceImpl.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TerraformExecutorServiceImpl.class);
                    TerraformExecutorServiceImpl executorService = context.getBean(TerraformExecutorServiceImpl.class);
                    assertThat(executorService.opaExecutorService).isNull();
                });
    }
}
