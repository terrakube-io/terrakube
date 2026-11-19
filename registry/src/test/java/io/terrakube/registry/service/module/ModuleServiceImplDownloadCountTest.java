package io.terrakube.registry.service.module;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.module.Module;
import io.terrakube.client.model.organization.module.ModuleAttributes;
import io.terrakube.client.model.organization.module.ModuleRequest;
import io.terrakube.client.model.response.Response;
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.service.search.CommonSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Downloads are counted in memory and written to the API once per flush, one GET and one PATCH per module.
class ModuleServiceImplDownloadCountTest {

    private TerrakubeClient terrakubeClient;
    private CommonSearchService commonSearchService;
    private ModuleServiceImpl moduleService;

    @BeforeEach
    void setup() {
        terrakubeClient = mock(TerrakubeClient.class);
        commonSearchService = mock(CommonSearchService.class);
        moduleService = new ModuleServiceImpl(terrakubeClient, mock(StorageService.class), commonSearchService);
        when(commonSearchService.getOrganizationId("org")).thenReturn("org-id");
    }

    private void moduleExists(List<Module> modules) {
        Response<List<Module>> response = new Response<>();
        response.setData(modules);
        when(terrakubeClient.getModuleByNameAndProvider("org-id", "module", "aws")).thenReturn(response);
    }

    // A fresh module per GET, as the API returns: the flush writes the new total into the module it read.
    private void moduleExists() {
        when(terrakubeClient.getModuleByNameAndProvider("org-id", "module", "aws")).thenAnswer(invocation -> {
            Module module = new Module();
            module.setId("module-id");
            ModuleAttributes attributes = new ModuleAttributes();
            attributes.setDownloadQuantity(4);
            module.setAttributes(attributes);
            Response<List<Module>> response = new Response<>();
            response.setData(List.of(module));
            return response;
        });
    }

    private int patchedDownloadQuantity(int patches) {
        ArgumentCaptor<ModuleRequest> request = ArgumentCaptor.forClass(ModuleRequest.class);
        verify(terrakubeClient, times(patches)).updateModule(request.capture(), anyString(), anyString());
        return request.getValue().getData().getAttributes().getDownloadQuantity();
    }

    private void download(int times) {
        for (int i = 0; i < times; i++) {
            moduleService.updateModuleDownloadCount("org", "module", "aws");
        }
    }

    @Test
    void downloadsAreWrittenAsOnePatchPerFlush() {
        moduleExists();
        download(5);

        moduleService.flushDownloadCounts();

        verify(terrakubeClient, times(1)).getModuleByNameAndProvider("org-id", "module", "aws");
        assertEquals(9, patchedDownloadQuantity(1));
    }

    @Test
    void flushWithoutDownloadsCallsNothing() {
        moduleExists();
        download(1);
        moduleService.flushDownloadCounts();

        moduleService.flushDownloadCounts();

        verify(terrakubeClient, times(1)).getModuleByNameAndProvider(anyString(), anyString(), anyString());
        verify(terrakubeClient, times(1)).updateModule(any(), anyString(), anyString());
    }

    @Test
    void failedPatchKeepsTheDownloadsForTheNextFlush() {
        moduleExists();
        doThrow(new RuntimeException("API restarting")).doNothing()
                .when(terrakubeClient).updateModule(any(), anyString(), anyString());
        download(3);
        moduleService.flushDownloadCounts();

        download(2);
        moduleService.flushDownloadCounts();

        assertEquals(9, patchedDownloadQuantity(2));
    }

    @Test
    void downloadsOfADeletedModuleAreDropped() {
        moduleExists(List.of());
        download(3);

        moduleService.flushDownloadCounts();
        moduleService.flushDownloadCounts();

        verify(terrakubeClient, times(1)).getModuleByNameAndProvider("org-id", "module", "aws");
        verify(terrakubeClient, never()).updateModule(any(), anyString(), anyString());
    }

    @Test
    void downloadsOfADeletedOrganizationAreDropped() {
        when(commonSearchService.getOrganizationId("gone")).thenReturn(null);
        moduleService.updateModuleDownloadCount("gone", "module", "aws");

        moduleService.flushDownloadCounts();
        moduleService.flushDownloadCounts();

        verify(commonSearchService, times(1)).getOrganizationId("gone");
        verifyNoInteractions(terrakubeClient);
    }

    @Test
    void flushStopsAtTheDeadlineWhenTheApiIsSlow() {
        when(commonSearchService.getOrganizationId("org")).thenAnswer(invocation -> {
            Thread.sleep(50);
            throw new RuntimeException("API unreachable");
        });
        moduleService.updateModuleDownloadCount("org", "first", "aws");
        moduleService.updateModuleDownloadCount("org", "second", "aws");

        moduleService.flushDownloadCounts(System.currentTimeMillis() + 10);

        verify(commonSearchService, times(1)).getOrganizationId("org");
    }

    @Test
    void shutdownFlushesPendingDownloads() {
        moduleExists();
        doNothing().when(terrakubeClient).updateModule(any(), anyString(), anyString());
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(ModuleServiceImpl.class, () -> moduleService);
        context.refresh();
        download(2);

        context.close();

        assertEquals(6, patchedDownloadQuantity(1));
    }
}
