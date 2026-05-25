package io.terrakube.api.plugin.storage.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.Encoders;
import io.jsonwebtoken.security.Keys;
import io.terrakube.api.plugin.storage.StorageTypeService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TerraformHttpBackendControllerTest {

    private static final String ORG = "org-1";
    private static final String WORKSPACE = "11111111-1111-1111-1111-111111111111";

    private StorageTypeService storage;
    private WorkspaceLockService lockService;
    private TerraformHttpBackendController controller;
    private String secretBase64Url;
    private SecretKey key;

    @BeforeEach
    void setUp() {
        storage = mock(StorageTypeService.class);
        lockService = mock(WorkspaceLockService.class);
        // HS256 matches NimbusJwtDecoder.withSecretKey(...).macAlgorithm(HS256)
        // — the same wiring DexAuthenticationManagerResolver uses for internal tokens.
        key = Jwts.SIG.HS256.key().build();
        secretBase64Url = Encoders.BASE64URL.encode(key.getEncoded());
        controller = new TerraformHttpBackendController(storage, lockService, secretBase64Url);

        // Tests interact with the controller's calls into the service, but the
        // controller also asks the service to parse the lock id out of the body
        // it received. Stub that with a tiny grep so the assertion focus stays
        // on controller behaviour rather than JSON plumbing.
        when(lockService.readLockId(anyString()))
                .thenAnswer(inv -> {
                    String body = inv.getArgument(0);
                    if (body == null || body.isBlank()) return null;
                    int idx = body.indexOf("\"ID\":\"");
                    if (idx < 0) return null;
                    int start = idx + "\"ID\":\"".length();
                    int end = body.indexOf("\"", start);
                    return end < 0 ? null : body.substring(start, end);
                });
    }

    private String validToken() {
        SecretKey signingKey = Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(secretBase64Url));
        return Jwts.builder()
                .subject("executor")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
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
        when(lockService.getLockInfo(WORKSPACE)).thenReturn(Optional.of("{\"ID\":\"holder-lock\"}"));

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.postState(ORG, WORKSPACE, "different-lock", request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("{\"ID\":\"holder-lock\"}", response.getBody());
        verify(storage, never()).uploadState(any(), any(), any(), any());
        verify(lockService, never()).refresh(WORKSPACE);
    }

    @Test
    void postStateSucceedsWhenNoLockExists() throws Exception {
        when(lockService.getLockInfo(WORKSPACE)).thenReturn(Optional.empty());

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{\"version\":4}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.postState(ORG, WORKSPACE, null, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(storage).uploadState(eq(ORG), eq(WORKSPACE), eq("{\"version\":4}"), eq("live"));
        verify(lockService, never()).refresh(WORKSPACE);
    }

    @Test
    void postStateSucceedsAndRefreshesLockWhenLockMatches() throws Exception {
        when(lockService.getLockInfo(WORKSPACE)).thenReturn(Optional.of("{\"ID\":\"matching-lock\"}"));

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{\"version\":4}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.postState(ORG, WORKSPACE, "matching-lock", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(storage).uploadState(eq(ORG), eq(WORKSPACE), eq("{\"version\":4}"), eq("live"));
        // Long applies write state once at the end. Refreshing TTL on the matching
        // post keeps the lock alive through subsequent steps in the same operation.
        verify(lockService).refresh(WORKSPACE);
    }

    @Test
    void deleteStateInvokesStorage() {
        ResponseEntity<String> response = controller.deleteState(ORG, WORKSPACE, withBearer(validToken()));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(storage).deleteCurrentTerraformState(ORG, WORKSPACE);
    }

    @Test
    void lockReturnsOkWhenAcquireSucceeds() throws Exception {
        when(lockService.tryAcquire(eq(WORKSPACE), anyString())).thenReturn(true);

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{\"ID\":\"client-lock\"}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.lock(ORG, WORKSPACE, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(lockService).tryAcquire(WORKSPACE, "{\"ID\":\"client-lock\"}");
    }

    @Test
    void lockIsIdempotentForSameHolder() throws Exception {
        when(lockService.tryAcquire(eq(WORKSPACE), anyString())).thenReturn(false);
        when(lockService.getLockInfo(WORKSPACE)).thenReturn(Optional.of("{\"ID\":\"client-lock\"}"));

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{\"ID\":\"client-lock\"}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.lock(ORG, WORKSPACE, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void lockReturns409WhenHeldByDifferentClient() throws Exception {
        when(lockService.tryAcquire(eq(WORKSPACE), anyString())).thenReturn(false);
        when(lockService.getLockInfo(WORKSPACE)).thenReturn(Optional.of("{\"ID\":\"holder-lock\"}"));

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{\"ID\":\"client-lock\"}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.lock(ORG, WORKSPACE, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("{\"ID\":\"holder-lock\"}", response.getBody());
    }

    @Test
    void lockReturns409WithFallbackBodyWhenKeyExpiredBetweenSetAndReadBack() throws Exception {
        // tryAcquire returned false (someone won the race) but the winning entry
        // expired before we could read it back. The controller still has to say 409
        // and supply *some* JSON body — default to {}.
        when(lockService.tryAcquire(eq(WORKSPACE), anyString())).thenReturn(false);
        when(lockService.getLockInfo(WORKSPACE)).thenReturn(Optional.empty());

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{\"ID\":\"client-lock\"}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.lock(ORG, WORKSPACE, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("{}", response.getBody());
    }

    @Test
    void unlockReturnsOkWhenNothingHeld() throws Exception {
        when(lockService.getLockInfo(WORKSPACE)).thenReturn(Optional.empty());

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{\"ID\":\"any\"}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.unlock(ORG, WORKSPACE, request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(lockService, never()).release(WORKSPACE);
    }

    @Test
    void unlockReturns409OnIdMismatch() throws Exception {
        when(lockService.getLockInfo(WORKSPACE)).thenReturn(Optional.of("{\"ID\":\"holder-lock\"}"));

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{\"ID\":\"other-lock\"}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.unlock(ORG, WORKSPACE, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("{\"ID\":\"holder-lock\"}", response.getBody());
        verify(lockService, never()).release(WORKSPACE);
    }

    @Test
    void unlockReleasesWhenIdMatches() throws Exception {
        when(lockService.getLockInfo(WORKSPACE)).thenReturn(Optional.of("{\"ID\":\"holder-lock\"}"));

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent("{\"ID\":\"holder-lock\"}".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response = controller.unlock(ORG, WORKSPACE, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(lockService).release(WORKSPACE);
    }

    @Test
    void unlockWithoutIdInBodyReleasesHeldLock() throws Exception {
        // terraform force-unlock can send an empty body. When no ID is supplied
        // the controller honors the unlock — operator override path.
        when(lockService.getLockInfo(WORKSPACE)).thenReturn(Optional.of("{\"ID\":\"holder-lock\"}"));

        MockHttpServletRequest request = withBasic(validToken());
        request.setContent(new byte[0]);

        ResponseEntity<String> response = controller.unlock(ORG, WORKSPACE, request);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(lockService).release(WORKSPACE);
    }
}
