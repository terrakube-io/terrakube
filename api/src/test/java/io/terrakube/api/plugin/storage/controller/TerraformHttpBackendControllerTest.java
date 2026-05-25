package io.terrakube.api.plugin.storage.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.Encoders;
import io.jsonwebtoken.security.Keys;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.WorkspaceStateLockRepository;
import io.terrakube.api.rs.workspace.state.lock.WorkspaceStateLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TerraformHttpBackendControllerTest {

    private static final String ORG = "org-1";
    private static final String WORKSPACE = "11111111-1111-1111-1111-111111111111";

    private StorageTypeService storage;
    private WorkspaceStateLockRepository lockRepo;
    private TerraformHttpBackendController controller;
    private String secretBase64Url;
    private SecretKey key;

    @BeforeEach
    void setUp() {
        storage = mock(StorageTypeService.class);
        lockRepo = mock(WorkspaceStateLockRepository.class);
        // Generate a fresh HS256 key for each test and feed it to the controller
        // in the same Base64URL form the API config supplies.
        key = Jwts.SIG.HS256.key().build();
        secretBase64Url = Encoders.BASE64URL.encode(key.getEncoded());
        controller = new TerraformHttpBackendController(storage, lockRepo, secretBase64Url);
    }

    private String validToken() {
        SecretKey signingKey = Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(secretBase64Url));
        return Jwts.builder()
                .subject("executor")
                .issuedAt(new Date())
                .signWith(signingKey)
                .compact();
    }

    private MockHttpServletRequest withBasic(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String credentials = "executor:" + token;
        request.addHeader("Authorization", "Basic " +
                Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    private MockHttpServletRequest withBearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    @Test
    void getStateReturns401WhenNoAuthHeader() {
        ResponseEntity<byte[]> response = controller.getState(ORG, WORKSPACE, new MockHttpServletRequest());
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void getStateReturns401WhenTokenIsInvalid() {
        ResponseEntity<byte[]> response = controller.getState(ORG, WORKSPACE, withBearer("not-a-real-jwt"));
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void getStateReturns401WhenAuthSchemeUnknown() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Digest username=foo");
        ResponseEntity<byte[]> response = controller.getState(ORG, WORKSPACE, request);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void getStateReturns404WhenStateMissing() {
        when(storage.getCurrentTerraformState(ORG, WORKSPACE)).thenReturn(new byte[0]);
        ResponseEntity<byte[]> response = controller.getState(ORG, WORKSPACE, withBasic(validToken()));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getStateReturnsBytesWhenPresent() {
        byte[] payload = "{\"version\":4}".getBytes(StandardCharsets.UTF_8);
        when(storage.getCurrentTerraformState(ORG, WORKSPACE)).thenReturn(payload);

        ResponseEntity<byte[]> response = controller.getState(ORG, WORKSPACE, withBearer(validToken()));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getHeaders().getContentType());
        assertEquals(payload.length, response.getBody().length);
    }

    @Test
    void postStateRejectsWithLockMismatch() throws Exception {
        WorkspaceStateLock held = new WorkspaceStateLock();
        held.setWorkspaceId(UUID.fromString(WORKSPACE));
        held.setLockId("holder-lock");
        held.setLockInfo("{\"ID\":\"holder-lock\"}");
        when(lockRepo.findById(UUID.fromString(WORKSPACE))).thenReturn(Optional.of(held));

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.postState(ORG, WORKSPACE, "different-lock", request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("{\"ID\":\"holder-lock\"}", response.getBody());
        verify(storage, never()).uploadState(any(), any(), any(), any());
    }

    @Test
    void postStateSucceedsWhenNoLockExists() throws Exception {
        when(lockRepo.findById(UUID.fromString(WORKSPACE))).thenReturn(Optional.empty());

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{\"version\":4}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.postState(ORG, WORKSPACE, null, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(storage).uploadState(eq(ORG), eq(WORKSPACE), eq("{\"version\":4}"), eq("live"));
    }

    @Test
    void postStateSucceedsWhenLockMatches() throws Exception {
        WorkspaceStateLock held = new WorkspaceStateLock();
        held.setWorkspaceId(UUID.fromString(WORKSPACE));
        held.setLockId("matching-lock");
        when(lockRepo.findById(UUID.fromString(WORKSPACE))).thenReturn(Optional.of(held));

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{\"version\":4}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.postState(ORG, WORKSPACE, "matching-lock", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(storage).uploadState(eq(ORG), eq(WORKSPACE), eq("{\"version\":4}"), eq("live"));
    }

    @Test
    void deleteStateInvokesStorage() {
        ResponseEntity<String> response = controller.deleteState(ORG, WORKSPACE, withBearer(validToken()));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(storage).deleteCurrentTerraformState(ORG, WORKSPACE);
    }

    @Test
    void lockCreatesNewLockWhenNoneHeld() throws Exception {
        when(lockRepo.findById(UUID.fromString(WORKSPACE))).thenReturn(Optional.empty());

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{\"ID\":\"client-lock\"}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.lock(ORG, WORKSPACE, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(lockRepo).saveAndFlush(any(WorkspaceStateLock.class));
    }

    @Test
    void lockIsIdempotentForSameHolder() throws Exception {
        WorkspaceStateLock existing = new WorkspaceStateLock();
        existing.setWorkspaceId(UUID.fromString(WORKSPACE));
        existing.setLockId("client-lock");
        existing.setLockInfo("{\"ID\":\"client-lock\"}");
        when(lockRepo.findById(UUID.fromString(WORKSPACE))).thenReturn(Optional.of(existing));

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{\"ID\":\"client-lock\"}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.lock(ORG, WORKSPACE, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(lockRepo, never()).saveAndFlush(any(WorkspaceStateLock.class));
    }

    @Test
    void lockReturns409WhenHeldByDifferentClient() throws Exception {
        WorkspaceStateLock existing = new WorkspaceStateLock();
        existing.setWorkspaceId(UUID.fromString(WORKSPACE));
        existing.setLockId("holder-lock");
        existing.setLockInfo("{\"ID\":\"holder-lock\"}");
        when(lockRepo.findById(UUID.fromString(WORKSPACE))).thenReturn(Optional.of(existing));

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{\"ID\":\"client-lock\"}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.lock(ORG, WORKSPACE, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("{\"ID\":\"holder-lock\"}", response.getBody());
        verify(lockRepo, never()).saveAndFlush(any(WorkspaceStateLock.class));
    }

    @Test
    void lockReturns409OnConcurrentInsertRace() throws Exception {
        // First findById: nothing. We try to insert and the DB rejects (race).
        // Second findById: the winner is now visible.
        UUID wsUuid = UUID.fromString(WORKSPACE);
        WorkspaceStateLock winner = new WorkspaceStateLock();
        winner.setWorkspaceId(wsUuid);
        winner.setLockId("winning-lock");
        winner.setLockInfo("{\"ID\":\"winning-lock\"}");
        when(lockRepo.findById(wsUuid))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        doThrow(new RuntimeException("constraint violation"))
                .when(lockRepo).saveAndFlush(any(WorkspaceStateLock.class));

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{\"ID\":\"loser-lock\"}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.lock(ORG, WORKSPACE, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("{\"ID\":\"winning-lock\"}", response.getBody());
    }

    @Test
    void unlockReturnsOkWhenNothingHeld() throws Exception {
        when(lockRepo.findById(UUID.fromString(WORKSPACE))).thenReturn(Optional.empty());

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{\"ID\":\"any\"}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.unlock(ORG, WORKSPACE, request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(lockRepo, never()).deleteById(any());
    }

    @Test
    void unlockReturns409OnIdMismatch() throws Exception {
        WorkspaceStateLock held = new WorkspaceStateLock();
        held.setWorkspaceId(UUID.fromString(WORKSPACE));
        held.setLockId("holder-lock");
        held.setLockInfo("{\"ID\":\"holder-lock\"}");
        when(lockRepo.findById(UUID.fromString(WORKSPACE))).thenReturn(Optional.of(held));

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{\"ID\":\"other-lock\"}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.unlock(ORG, WORKSPACE, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("{\"ID\":\"holder-lock\"}", response.getBody());
        verify(lockRepo, never()).deleteById(any());
    }

    @Test
    void unlockDeletesWhenIdMatches() throws Exception {
        WorkspaceStateLock held = new WorkspaceStateLock();
        held.setWorkspaceId(UUID.fromString(WORKSPACE));
        held.setLockId("holder-lock");
        when(lockRepo.findById(UUID.fromString(WORKSPACE))).thenReturn(Optional.of(held));

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{\"ID\":\"holder-lock\"}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.unlock(ORG, WORKSPACE, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(lockRepo).deleteById(UUID.fromString(WORKSPACE));
    }

    @Test
    void unlockWithoutIdInBodyDeletesHeldLock() throws Exception {
        // terraform force-unlock can send an empty body or different ID.
        // When no ID is supplied (null after parse), the controller honors the unlock.
        WorkspaceStateLock held = new WorkspaceStateLock();
        held.setWorkspaceId(UUID.fromString(WORKSPACE));
        held.setLockId("holder-lock");
        when(lockRepo.findById(UUID.fromString(WORKSPACE))).thenReturn(Optional.of(held));

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent(new byte[0]);

        ResponseEntity<String> response = controller.unlock(ORG, WORKSPACE, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(lockRepo).deleteById(UUID.fromString(WORKSPACE));
    }
}
