package io.terrakube.registry.service.module;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.module.Module;
import io.terrakube.client.model.organization.module.ModuleAttributes;
import io.terrakube.client.model.organization.module.Relationships;
import io.terrakube.client.model.organization.module.SshData;
import io.terrakube.client.model.organization.module.version.ModuleVersion;
import io.terrakube.client.model.organization.module.version.ModuleVersionAttributes;
import io.terrakube.client.model.organization.workspace.VcsData;
import io.terrakube.client.model.response.Response;
import io.terrakube.registry.configuration.CacheConfig;
import io.terrakube.registry.configuration.OpenRegistryProperties;
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.service.search.CommonSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Verifies the sync = true behavior added to ModuleServiceImpl#getModuleVersionPath: a burst of
// concurrent requests for the same not-yet-cached module version must coalesce into a single
// resolution, not one call per concurrent request (the thundering-herd goal of the module-download
// resilience design). Uses a real Spring cache-AOP context (not a plain Mockito unit test) because
// sync = true is enforced by the Caffeine-backed cache proxy, not by application code.
class ModuleServiceImplCacheTest {

    private static final int CONCURRENT_REQUESTS = 20;

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Configuration
    @EnableCaching
    static class TestConfig {
        @Bean
        CacheConfig cacheConfig() {
            return new CacheConfig();
        }

        @Bean
        OpenRegistryProperties openRegistryProperties() {
            OpenRegistryProperties properties = new OpenRegistryProperties();
            properties.setModuleVersionsCacheTtlSeconds(600);
            return properties;
        }

        @Bean
        org.springframework.cache.CacheManager cacheManager(CacheConfig cacheConfig,
                OpenRegistryProperties openRegistryProperties) {
            return cacheConfig.cacheManager(openRegistryProperties);
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

    @Test
    void concurrentCacheMissesResolveOnce() throws Exception {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        TerrakubeClient terrakubeClient = context.getBean(TerrakubeClient.class);
        CommonSearchService commonSearchService = context.getBean(CommonSearchService.class);
        StorageService storageService = context.getBean(StorageService.class);
        ModuleService moduleService = context.getBean(ModuleService.class);

        when(commonSearchService.getOrganizationId("org")).thenAnswer(invocation -> {
            // Widens the race window so concurrent callers pile up while resolution is in flight.
            Thread.sleep(300);
            return "org-id";
        });

        Module module = new Module();
        module.setId("module-id");
        ModuleAttributes attributes = new ModuleAttributes();
        attributes.setSource("git::https://example.com/org/module.git");
        attributes.setFolder(null);
        attributes.setTagPrefix(null);
        module.setAttributes(attributes);
        Relationships relationships = new Relationships();
        relationships.setVcs(new VcsData());
        relationships.setSsh(new SshData());
        module.setRelationships(relationships);

        Response<List<Module>> moduleResponse = new Response<>();
        moduleResponse.setData(List.of(module));
        when(terrakubeClient.getModuleByNameAndProvider("org-id", "module", "aws")).thenReturn(moduleResponse);

        ModuleVersion moduleVersion = new ModuleVersion();
        ModuleVersionAttributes versionAttributes = new ModuleVersionAttributes();
        versionAttributes.setVersion("1.0.0");
        versionAttributes.setGitTag("v1.0.0");
        moduleVersion.setAttributes(versionAttributes);

        Response<List<ModuleVersion>> versionResponse = new Response<>();
        versionResponse.setData(List.of(moduleVersion));
        when(terrakubeClient.getAllVersionsByOrganizationIdAndModuleId("org-id", "module-id"))
                .thenReturn(versionResponse);

        when(storageService.searchModule(anyString(), anyString(), anyString(), any()))
                .thenReturn("https://registry.example.com/terraform/modules/v1/download/org/module/aws/1.0.0/module.zip");

        int threadCount = CONCURRENT_REQUESTS;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    moduleService.getModuleVersionPath("org", "module", "aws", "1.0.0");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executorService.shutdown();

        verify(commonSearchService, times(1)).getOrganizationId("org");
        verify(terrakubeClient, times(1)).getModuleByNameAndProvider("org-id", "module", "aws");
        verify(storageService, times(1)).searchModule(anyString(), anyString(), anyString(), any());
    }

    @Test
    void cacheHitMakesNoFurtherCalls() throws Exception {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        TerrakubeClient terrakubeClient = context.getBean(TerrakubeClient.class);
        CommonSearchService commonSearchService = context.getBean(CommonSearchService.class);
        StorageService storageService = context.getBean(StorageService.class);
        ModuleService moduleService = context.getBean(ModuleService.class);

        when(commonSearchService.getOrganizationId("org")).thenReturn("org-id");

        Module module = new Module();
        module.setId("module-id");
        ModuleAttributes attributes = new ModuleAttributes();
        attributes.setSource("git::https://example.com/org/module.git");
        module.setAttributes(attributes);
        Relationships relationships = new Relationships();
        relationships.setVcs(new VcsData());
        relationships.setSsh(new SshData());
        module.setRelationships(relationships);

        Response<List<Module>> moduleResponse = new Response<>();
        moduleResponse.setData(List.of(module));
        when(terrakubeClient.getModuleByNameAndProvider("org-id", "module", "aws")).thenReturn(moduleResponse);

        ModuleVersion moduleVersion = new ModuleVersion();
        ModuleVersionAttributes versionAttributes = new ModuleVersionAttributes();
        versionAttributes.setVersion("1.0.0");
        versionAttributes.setGitTag("v1.0.0");
        moduleVersion.setAttributes(versionAttributes);

        Response<List<ModuleVersion>> versionResponse = new Response<>();
        versionResponse.setData(List.of(moduleVersion));
        when(terrakubeClient.getAllVersionsByOrganizationIdAndModuleId("org-id", "module-id"))
                .thenReturn(versionResponse);

        when(storageService.searchModule(anyString(), anyString(), anyString(), any()))
                .thenReturn("https://registry.example.com/terraform/modules/v1/download/org/module/aws/1.0.0/module.zip");

        String first = moduleService.getModuleVersionPath("org", "module", "aws", "1.0.0");
        String second = moduleService.getModuleVersionPath("org", "module", "aws", "1.0.0");

        assertEquals(first, second);
        verify(commonSearchService, times(1)).getOrganizationId("org");
        verify(terrakubeClient, times(1)).getModuleByNameAndProvider(anyString(), anyString(), anyString());
        verify(storageService, times(1)).searchModule(anyString(), anyString(), anyString(), any());
    }

    @Test
    void getModuleVersionPathWithNullGitTagDoesNotThrowException() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        TerrakubeClient terrakubeClient = context.getBean(TerrakubeClient.class);
        CommonSearchService commonSearchService = context.getBean(CommonSearchService.class);
        StorageService storageService = context.getBean(StorageService.class);
        ModuleService moduleService = context.getBean(ModuleService.class);

        when(commonSearchService.getOrganizationId("org")).thenReturn("org-id");

        Module module = new Module();
        module.setId("module-id");
        ModuleAttributes attributes = new ModuleAttributes();
        attributes.setSource("git::https://example.com/org/module.git");
        module.setAttributes(attributes);
        Relationships relationships = new Relationships();
        relationships.setVcs(new VcsData());
        relationships.setSsh(new SshData());
        module.setRelationships(relationships);

        Response<List<Module>> moduleResponse = new Response<>();
        moduleResponse.setData(List.of(module));
        when(terrakubeClient.getModuleByNameAndProvider("org-id", "module", "aws")).thenReturn(moduleResponse);

        ModuleVersion moduleVersion = new ModuleVersion();
        ModuleVersionAttributes versionAttributes = new ModuleVersionAttributes();
        versionAttributes.setVersion("1.0.0");
        versionAttributes.setGitTag(null);
        moduleVersion.setAttributes(versionAttributes);

        Response<List<ModuleVersion>> versionResponse = new Response<>();
        versionResponse.setData(List.of(moduleVersion));
        when(terrakubeClient.getAllVersionsByOrganizationIdAndModuleId("org-id", "module-id"))
                .thenReturn(versionResponse);

        org.mockito.ArgumentCaptor<io.terrakube.registry.service.git.ModuleVersionDownload> captor =
                org.mockito.ArgumentCaptor.forClass(io.terrakube.registry.service.git.ModuleVersionDownload.class);
        when(storageService.searchModule(anyString(), anyString(), anyString(), captor.capture()))
                .thenReturn("https://registry.example.com/terraform/modules/v1/download/org/module/aws/1.0.0/module.zip");

        String result = moduleService.getModuleVersionPath("org", "module", "aws", "1.0.0");

        assertEquals("https://registry.example.com/terraform/modules/v1/download/org/module/aws/1.0.0/module.zip", result);
        org.junit.jupiter.api.Assertions.assertNull(captor.getValue().gitTag());
    }

    @Test
    void getModuleVersionPathWithNullVersionResponseDoesNotThrowException() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        TerrakubeClient terrakubeClient = context.getBean(TerrakubeClient.class);
        CommonSearchService commonSearchService = context.getBean(CommonSearchService.class);
        StorageService storageService = context.getBean(StorageService.class);
        ModuleService moduleService = context.getBean(ModuleService.class);

        when(commonSearchService.getOrganizationId("org")).thenReturn("org-id");

        Module module = new Module();
        module.setId("module-id");
        ModuleAttributes attributes = new ModuleAttributes();
        attributes.setSource("git::https://example.com/org/module.git");
        module.setAttributes(attributes);
        Relationships relationships = new Relationships();
        relationships.setVcs(new VcsData());
        relationships.setSsh(new SshData());
        module.setRelationships(relationships);

        Response<List<Module>> moduleResponse = new Response<>();
        moduleResponse.setData(List.of(module));
        when(terrakubeClient.getModuleByNameAndProvider("org-id", "module", "aws")).thenReturn(moduleResponse);

        when(terrakubeClient.getAllVersionsByOrganizationIdAndModuleId("org-id", "module-id"))
                .thenReturn(null);

        org.mockito.ArgumentCaptor<io.terrakube.registry.service.git.ModuleVersionDownload> captor =
                org.mockito.ArgumentCaptor.forClass(io.terrakube.registry.service.git.ModuleVersionDownload.class);
        when(storageService.searchModule(anyString(), anyString(), anyString(), captor.capture()))
                .thenReturn("https://registry.example.com/terraform/modules/v1/download/org/module/aws/1.0.0/module.zip");

        String result = moduleService.getModuleVersionPath("org", "module", "aws", "1.0.0");

        assertEquals("https://registry.example.com/terraform/modules/v1/download/org/module/aws/1.0.0/module.zip", result);
        org.junit.jupiter.api.Assertions.assertNull(captor.getValue().gitTag());
    }
}
