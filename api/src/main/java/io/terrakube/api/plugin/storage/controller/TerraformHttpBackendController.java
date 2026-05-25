package io.terrakube.api.plugin.storage.controller;

import io.jsonwebtoken.io.Decoders;
import io.terrakube.api.plugin.storage.StorageTypeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Implements the Terraform "http" backend protocol so executors (agents) never
 * need direct egress to the configured object store. Authentication uses HTTP
 * Basic — the Terraform http backend cannot send custom headers, but supports
 * username/password. We accept any username and validate the password as an
 * internal HMAC-signed JWT using the same NimbusJwtDecoder configuration the
 * rest of the API uses for the {@code TerrakubeInternal} issuer.
 */
@RestController
@Slf4j
@RequestMapping("/tfstate/v1/http-backend")
public class TerraformHttpBackendController {

    private final StorageTypeService storageTypeService;
    private final WorkspaceLockService lockService;
    private final JwtDecoder internalJwtDecoder;

    public TerraformHttpBackendController(StorageTypeService storageTypeService,
                                          WorkspaceLockService lockService,
                                          @Value("${io.terrakube.token.internal}") String internalJwtSecret) {
        this.storageTypeService = storageTypeService;
        this.lockService = lockService;
        SecretKey jwtSecretKey = new SecretKeySpec(Decoders.BASE64URL.decode(internalJwtSecret), "HMACSHA256");
        this.internalJwtDecoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @GetMapping(value = "/organization/{organizationId}/workspace/{workspaceId}/state",
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> getState(@PathVariable("organizationId") String organizationId,
                                           @PathVariable("workspaceId") String workspaceId,
                                           HttpServletRequest request) {
        if (!isAuthorized(request)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        byte[] data = storageTypeService.getCurrentTerraformState(organizationId, workspaceId);
        if (data == null || data.length == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(data);
    }

    @PostMapping(value = "/organization/{organizationId}/workspace/{workspaceId}/state")
    public ResponseEntity<String> postState(@PathVariable("organizationId") String organizationId,
                                            @PathVariable("workspaceId") String workspaceId,
                                            @RequestParam(value = "ID", required = false) String lockId,
                                            HttpServletRequest request) throws IOException {
        if (!isAuthorized(request)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        Optional<String> heldLockInfo = lockService.getLockInfo(workspaceId);
        if (heldLockInfo.isPresent()) {
            String heldLockId = lockService.readLockId(heldLockInfo.get());
            if (lockId == null || !lockId.equals(heldLockId)) {
                log.warn("Rejecting state write for workspace {}: lock mismatch (request={}, holder={})",
                        workspaceId, lockId, heldLockId);
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(heldLockInfo.get());
            }
            // Long applies write state once at the end; refresh TTL so a generous
            // operation still finishes before the lock expires.
            lockService.refresh(workspaceId);
        }

        String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        // historyId is intentionally "live" here — the live state write from the
        // http backend is not a history snapshot. History entries are created
        // separately by the executor via the dedicated history endpoints.
        storageTypeService.uploadState(organizationId, workspaceId, body, "live");
        return ResponseEntity.ok("");
    }

    @DeleteMapping(value = "/organization/{organizationId}/workspace/{workspaceId}/state")
    public ResponseEntity<String> deleteState(@PathVariable("organizationId") String organizationId,
                                              @PathVariable("workspaceId") String workspaceId,
                                              HttpServletRequest request) {
        if (!isAuthorized(request)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        storageTypeService.deleteCurrentTerraformState(organizationId, workspaceId);
        return ResponseEntity.ok("");
    }

    @PostMapping(value = "/organization/{organizationId}/workspace/{workspaceId}/lock")
    public ResponseEntity<String> lock(@PathVariable("organizationId") String organizationId,
                                       @PathVariable("workspaceId") String workspaceId,
                                       HttpServletRequest request) throws IOException {
        if (!isAuthorized(request)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String incomingLockId = lockService.readLockId(body);

        // SET NX EX: only one writer wins. If something is already there, treat
        // an exact-match incoming id as an idempotent re-lock (terraform retries
        // the LOCK on transient failures), anything else as a real conflict.
        if (lockService.tryAcquire(workspaceId, body)) {
            return ResponseEntity.ok("");
        }
        Optional<String> heldLockInfo = lockService.getLockInfo(workspaceId);
        String heldLockId = heldLockInfo.map(lockService::readLockId).orElse(null);
        if (incomingLockId != null && incomingLockId.equals(heldLockId)) {
            return ResponseEntity.ok("");
        }
        log.warn("Lock conflict on workspace {}: held by {}, requester sent {}", workspaceId, heldLockId, incomingLockId);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(heldLockInfo.orElse("{}"));
    }

    @DeleteMapping(value = "/organization/{organizationId}/workspace/{workspaceId}/lock")
    public ResponseEntity<String> unlock(@PathVariable("organizationId") String organizationId,
                                         @PathVariable("workspaceId") String workspaceId,
                                         HttpServletRequest request) throws IOException {
        if (!isAuthorized(request)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String incomingLockId = lockService.readLockId(body);

        Optional<String> heldLockInfo = lockService.getLockInfo(workspaceId);
        if (heldLockInfo.isEmpty()) {
            return ResponseEntity.ok("");
        }
        String heldLockId = lockService.readLockId(heldLockInfo.get());
        // force-unlock (terraform force-unlock) sends no body or a different ID; honor it
        if (incomingLockId != null && heldLockId != null && !heldLockId.equals(incomingLockId)) {
            log.warn("Unlock id mismatch on workspace {}: holder={}, requester={}", workspaceId, heldLockId, incomingLockId);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(heldLockInfo.get());
        }
        lockService.release(workspaceId);
        return ResponseEntity.ok("");
    }

    private boolean isAuthorized(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null) {
            return false;
        }
        String token;
        if (header.startsWith("Basic ")) {
            try {
                String decoded = new String(Base64.getDecoder().decode(header.substring("Basic ".length())), StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                token = colon >= 0 ? decoded.substring(colon + 1) : decoded;
            } catch (IllegalArgumentException e) {
                return false;
            }
        } else if (header.startsWith("Bearer ")) {
            token = header.substring("Bearer ".length());
        } else {
            return false;
        }
        if (token == null || token.isBlank()) return false;
        try {
            internalJwtDecoder.decode(token);
            return true;
        } catch (JwtException e) {
            log.warn("Rejected http-backend request: invalid token ({})", e.getMessage());
            return false;
        }
    }

}
