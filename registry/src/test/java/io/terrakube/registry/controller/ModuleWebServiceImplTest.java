package io.terrakube.registry.controller;

import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.plugin.storage.StorageUnavailableException;
import io.terrakube.registry.service.module.ModuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Standalone MockMvc test (no full Spring context / WireMock) covering the module.zip endpoint's
// two branches added by the module-download resilience design: redirecting to a presigned S3 URL
// when the storage backend supports it, and mapping a storage failure to a retryable 503 rather
// than an opaque 500 or an ambiguous empty body.
class ModuleWebServiceImplTest {

    private ModuleService moduleService;
    private StorageService storageService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        moduleService = mock(ModuleService.class);
        storageService = mock(StorageService.class);

        ModuleWebServiceImpl controller = new ModuleWebServiceImpl();
        controller.moduleService = moduleService;
        controller.storageService = storageService;

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new StorageExceptionHandler())
                .build();
    }

    @Test
    void redirectsToPresignedUrlWhenStorageProvidesOne() throws Exception {
        URI presignedUrl = URI.create("https://test-bucket.s3.amazonaws.com/registry/org/module/aws/1.0.0/module.zip?X-Amz-Signature=redacted");
        when(storageService.getPresignedDownloadUrl("org", "module", "aws", "1.0.0"))
                .thenReturn(Optional.of(presignedUrl));

        mockMvc.perform(get("/terraform/modules/v1/download/org/module/aws/1.0.0/module.zip"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", presignedUrl.toString()));

        verify(storageService).getPresignedDownloadUrl("org", "module", "aws", "1.0.0");
        verifyNoMoreInteractions(storageService);
    }

    @Test
    void proxiesBytesWhenNoPresignedUrlAvailable() throws Exception {
        when(storageService.getPresignedDownloadUrl("org", "module", "aws", "1.0.0"))
                .thenReturn(Optional.empty());
        byte[] zipBytes = "zip-content".getBytes();
        when(storageService.downloadModule("org", "module", "aws", "1.0.0")).thenReturn(zipBytes);

        mockMvc.perform(get("/terraform/modules/v1/download/org/module/aws/1.0.0/module.zip"))
                .andExpect(status().isOk());

        verify(storageService).downloadModule("org", "module", "aws", "1.0.0");
    }

    @Test
    void returnsRetryableErrorWhenStorageUnavailable() throws Exception {
        when(storageService.getPresignedDownloadUrl(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new StorageUnavailableException("boom", new RuntimeException("s3 timeout")));

        mockMvc.perform(get("/terraform/modules/v1/download/org/module/aws/1.0.0/module.zip"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));
    }
}
