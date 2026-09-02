package io.terrakube.registry.service.module;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.module.Module;
import io.terrakube.client.model.organization.module.ModuleAttributes;
import io.terrakube.client.model.organization.module.ModuleRequest;
import io.terrakube.client.model.response.Response;
import io.terrakube.registry.configuration.DownloadCountExecutorConfig;
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.service.search.CommonSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Verifies updateModuleDownloadCount runs on the dedicated downloadCountExecutor
// (DownloadCountExecutorConfig) instead of the caller's thread, and that a transient failure is
// retried with bounded backoff rather than being silently dropped on the first error - see the
// module-download resilience design's "download-count updates must not delay Terraform's metadata
// response" goal.
class ModuleServiceImplAsyncDownloadCountTest {

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Configuration
    @EnableAsync
    static class TestConfig {
        @Bean
        DownloadCountExecutorConfig downloadCountExecutorConfig() {
            return new DownloadCountExecutorConfig();
        }

        @Bean("downloadCountExecutor")
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor downloadCountExecutor(
                DownloadCountExecutorConfig config) {
            return config.downloadCountExecutor(2, 5, 200);
        }

        @Bean
        TerrakubeClient terrakubeClient() {
            return mock(TerrakubeClient.class);
        }

        @Bean
        CommonSearchService commonSearchService() {
            return mock(CommonSearchService.class);
        }

        @Bean
        StorageService storageService() {
            return mock(StorageService.class);
        }

        @Bean
        ModuleServiceImpl moduleService(TerrakubeClient terrakubeClient, StorageService storageService,
                CommonSearchService commonSearchService) {
            return new ModuleServiceImpl(terrakubeClient, storageService, commonSearchService);
        }
    }

    private void stubModuleLookup(TerrakubeClient terrakubeClient, CommonSearchService commonSearchService) {
        when(commonSearchService.getOrganizationId("org")).thenReturn("org-id");

        Module module = new Module();
        module.setId("module-id");
        ModuleAttributes attributes = new ModuleAttributes();
        attributes.setDownloadQuantity(4);
        module.setAttributes(attributes);

        Response<List<Module>> moduleResponse = new Response<>();
        moduleResponse.setData(List.of(module));
        when(terrakubeClient.getModuleByNameAndProvider("org-id", "module", "aws")).thenReturn(moduleResponse);
    }

    @Test
    void updateModuleDownloadCountDoesNotBlockCaller() throws Exception {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        TerrakubeClient terrakubeClient = context.getBean(TerrakubeClient.class);
        CommonSearchService commonSearchService = context.getBean(CommonSearchService.class);
        ModuleService moduleService = context.getBean(ModuleService.class);
        stubModuleLookup(terrakubeClient, commonSearchService);

        CountDownLatch updateInvoked = new CountDownLatch(1);
        doAnswer(invocation -> {
            Thread.sleep(500);
            updateInvoked.countDown();
            return null;
        }).when(terrakubeClient).updateModule(any(ModuleRequest.class), anyString(), anyString());

        long start = System.nanoTime();
        moduleService.updateModuleDownloadCount("org", "module", "aws");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < 400, "caller blocked for " + elapsedMillis + "ms on a call that should return immediately");
        assertTrue(updateInvoked.await(2, TimeUnit.SECONDS), "async update never ran");
    }

    @Test
    void updateModuleDownloadCountRetriesTransientFailure() throws Exception {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        TerrakubeClient terrakubeClient = context.getBean(TerrakubeClient.class);
        CommonSearchService commonSearchService = context.getBean(CommonSearchService.class);
        ModuleService moduleService = context.getBean(ModuleService.class);
        stubModuleLookup(terrakubeClient, commonSearchService);

        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch thirdAttempt = new CountDownLatch(1);
        doAnswer(invocation -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                throw new RuntimeException("transient failure " + attempt);
            }
            thirdAttempt.countDown();
            return null;
        }).when(terrakubeClient).updateModule(any(ModuleRequest.class), anyString(), anyString());

        moduleService.updateModuleDownloadCount("org", "module", "aws");

        assertTrue(thirdAttempt.await(5, TimeUnit.SECONDS), "did not retry through to a successful attempt");
    }
}
