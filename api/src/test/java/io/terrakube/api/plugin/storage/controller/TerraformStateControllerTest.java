package io.terrakube.api.plugin.storage.controller;

import io.terrakube.api.plugin.security.state.StateService;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.ArchiveRepository;
import io.terrakube.api.repository.HistoryRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TerraformStateControllerTest {

    @Mock
    private StorageTypeService storageTypeService;
    @Mock
    private ArchiveRepository archiveRepository;
    @Mock
    private WorkspaceRepository workspaceRepository;
    @Mock
    private HistoryRepository historyRepository;
    @Mock
    private StateService stateService;

    private TerraformStateController controller;

    private static final String ORG_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String WS_ID = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";
    private static final String AUTH_HEADER = "Basic dGVycmFmb3JtOnRva2Vu";

    @BeforeEach
    void setUp() {
        controller = new TerraformStateController(
                storageTypeService,
                archiveRepository,
                workspaceRepository,
                historyRepository,
                "terrakube-api.localhost"
        );
        ReflectionTestUtils.setField(controller, "stateService", stateService);
    }

    @Test
    void getHttpBackendState_Returns204WhenNoStateExists() {
        when(stateService.authorizeHttpBackendAccess(AUTH_HEADER, ORG_ID, WS_ID))
                .thenReturn(StateService.HttpBackendAccessDecision.ALLOWED);
        when(storageTypeService.getCurrentTerraformState(ORG_ID, WS_ID))
                .thenReturn(new byte[0]);

        ResponseEntity<byte[]> response = controller.getHttpBackendState(AUTH_HEADER, ORG_ID, WS_ID);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void getHttpBackendState_Returns200WithStatePayload() {
        byte[] statePayload = "{\"version\":4,\"serial\":1}".getBytes(StandardCharsets.UTF_8);
        when(stateService.authorizeHttpBackendAccess(AUTH_HEADER, ORG_ID, WS_ID))
                .thenReturn(StateService.HttpBackendAccessDecision.ALLOWED);
        when(storageTypeService.getCurrentTerraformState(ORG_ID, WS_ID))
                .thenReturn(statePayload);

        ResponseEntity<byte[]> response = controller.getHttpBackendState(AUTH_HEADER, ORG_ID, WS_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        assertArrayEquals(statePayload, response.getBody());
    }

    @Test
    void getHttpBackendState_Returns401And403WhenDenied() {
        when(stateService.authorizeHttpBackendAccess(null, ORG_ID, WS_ID))
                .thenReturn(StateService.HttpBackendAccessDecision.UNAUTHORIZED);
        assertEquals(HttpStatus.UNAUTHORIZED, controller.getHttpBackendState(null, ORG_ID, WS_ID).getStatusCode());

        when(stateService.authorizeHttpBackendAccess(AUTH_HEADER, ORG_ID, WS_ID))
                .thenReturn(StateService.HttpBackendAccessDecision.FORBIDDEN);
        assertEquals(HttpStatus.FORBIDDEN, controller.getHttpBackendState(AUTH_HEADER, ORG_ID, WS_ID).getStatusCode());
        verify(storageTypeService, never()).getCurrentTerraformState(anyString(), anyString());
    }

    @Test
    void updateHttpBackendState_SavesStateAndVerifiesContentMd5() throws Exception {
        byte[] statePayload = "{\"version\":4,\"serial\":2}".getBytes(StandardCharsets.UTF_8);
        String validMd5 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(statePayload));
        when(stateService.authorizeHttpBackendAccess(AUTH_HEADER, ORG_ID, WS_ID))
                .thenReturn(StateService.HttpBackendAccessDecision.ALLOWED);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(statePayload);

        ResponseEntity<Void> response = controller.updateHttpBackendState(request, AUTH_HEADER, validMd5, ORG_ID, WS_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(storageTypeService).saveCurrentTerraformState(ORG_ID, WS_ID, statePayload);
    }

    @Test
    void updateHttpBackendState_RejectsInvalidContentMd5With400() throws IOException {
        byte[] statePayload = "{\"version\":4,\"serial\":2}".getBytes(StandardCharsets.UTF_8);
        when(stateService.authorizeHttpBackendAccess(AUTH_HEADER, ORG_ID, WS_ID))
                .thenReturn(StateService.HttpBackendAccessDecision.ALLOWED);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(statePayload);

        ResponseEntity<Void> response = controller.updateHttpBackendState(request, AUTH_HEADER, "invalid-md5", ORG_ID, WS_ID);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(storageTypeService, never()).saveCurrentTerraformState(anyString(), anyString(), any());
    }

    @Test
    void deleteHttpBackendState_DeletesStateWhenAuthorized() {
        when(stateService.authorizeHttpBackendAccess(AUTH_HEADER, ORG_ID, WS_ID))
                .thenReturn(StateService.HttpBackendAccessDecision.ALLOWED);

        ResponseEntity<Void> response = controller.deleteHttpBackendState(AUTH_HEADER, ORG_ID, WS_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(storageTypeService).deleteCurrentTerraformState(ORG_ID, WS_ID);
    }
}

