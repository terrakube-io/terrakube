package io.terrakube.executor.service.terraform.structured;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.configuration.StructuredOutputProperties;
import io.terrakube.executor.service.logs.ProcessLogs;
import io.terrakube.executor.service.terraform.ApplyStructuredOutputService;
import io.terrakube.executor.service.terraform.DefaultStructuredSnapshotPersister;
import io.terrakube.executor.service.terraform.JobContextService;
import io.terrakube.executor.service.terraform.PlanStructuredOutputService;
import io.terrakube.terraform.TerraformClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The structured-output beans form a construction cycle (services -> queue -> persister -> services)
 * that {@code @Lazy} on the queue's persister dependency must break. No {@code @SpringBootTest}
 * precedent in this module, so this drives just the relevant beans through the container.
 */
class StructuredOutputWiringTest {

    @Configuration
    static class Collaborators {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
        @Bean StructuredOutputProperties structuredOutputProperties() { return new StructuredOutputProperties(); }
        @Bean ExecutorFlagsProperties executorFlagsProperties() { return new ExecutorFlagsProperties(); }
        @Bean JobContextService jobContextService() { return Mockito.mock(JobContextService.class); }
        @Bean ProcessLogs processLogs() { return Mockito.mock(ProcessLogs.class); }
        @Bean TerraformClient terraformClient() { return Mockito.mock(TerraformClient.class); }
        @Bean TerrakubeClient terrakubeClient() { return Mockito.mock(TerrakubeClient.class); }
    }

    @Test
    void theStructuredOutputBeanGraphStartsWithoutACircularDependency() {
        new ApplicationContextRunner()
                .withUserConfiguration(Collaborators.class)
                .withBean(StructuredOutputPersistenceQueue.class)
                .withBean(DefaultStructuredSnapshotPersister.class)
                .withBean(PlanStructuredOutputService.class)
                .withBean(ApplyStructuredOutputService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(StructuredOutputPersistenceQueue.class);
                    assertThat(context).hasSingleBean(DefaultStructuredSnapshotPersister.class);
                });
    }
}
