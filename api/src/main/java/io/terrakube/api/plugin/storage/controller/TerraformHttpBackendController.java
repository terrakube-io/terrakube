package io.terrakube.api.plugin.storage.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.WorkspaceStateLockRepository;
import io.terrakube.api.rs.workspace.state.lock.WorkspaceStateLock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements the Terraform "http" backend protocol so executors (agents) never
 * need direct egress to the configured object store. Authentication uses HTTP
 * Basic — the Terraform http backend cannot send custom headers, but supports
 * username/password. We accept any username and validate the password as an
 * internal HMAC-signed JWT (same secret used by the rest of the API).
 */
@RestController
@Slf4j
@RequestMapping("/tfstate/v1/http-backend")
public class TerraformHttpBackendController {

    private final StorageTypeService storageTypeService;
    private final WorkspaceStateLockRepository workspaceStateLockRepository;
    private final SecretKey internalJwtKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TerraformHttpBackendController(StorageTypeService storageTypeService,
                                          WorkspaceStateLockRepository workspaceStateLockRepository,
                                          @Value("${io.terrakube.token.internal}") String internalJwtSecret) {
        this.storageTypeService = storageTypeService;
        this.workspaceStateLockRepository = workspaceStateLockRepository;
        this.internalJwtKey = new SecretKeySpec(Decoders.BASE64URL.decode(internalJwtSecret), "HmacSHA256");
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

        Optional<WorkspaceStateLock> currentLock = workspaceStateLockRepository.findById(UUID.fromString(workspaceId));
        if (currentLock.isPresent()) {
            if (lockId == null || !lockId.equals(currentLock.get().getLockId())) {
                log.warn("Rejecting state write for workspace {}: lock mismatch (request={}, holder={})",
                        workspaceId, lockId, currentLock.get().getLockId());
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(currentLock.get().getLockInfo() != null ? currentLock.get().getLockInfo() : "{}");
            }
        }

        String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        // historyId is intentionally empty here — the live state write from the
        // http backend is not a history snapshot. History entries are created
        // separately by the executor via the dedicated history endpoints below.
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
    @Transactional
    public ResponseEntity<String> lock(@PathVariable("organizationId") String organizationId,
                                       @PathVariable("workspaceId") String workspaceId,
                                       HttpServletRequest request) throws IOException {
        if (!isAuthorized(request)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String incomingLockId = readLockId(body);

        UUID wsUuid = UUID.fromString(workspaceId);
        Optional<WorkspaceStateLock> existing = workspaceStateLockRepository.findById(wsUuid);
        if (existing.isPresent()) {
            WorkspaceStateLock lock = existing.get();
            if (incomingLockId != null && incomingLockId.equals(lock.getLockId())) {
                // idempotent re-lock by the same holder
                return ResponseEntity.ok("");
            }
            log.warn("Lock conflict on workspace {}: held by {}, requester sent {}", workspaceId, lock.getLockId(), incomingLockId);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(lock.getLockInfo() != null ? lock.getLockInfo() : "{}");
        }

        WorkspaceStateLock newLock = new WorkspaceStateLock();
        newLock.setWorkspaceId(wsUuid);
        newLock.setLockId(incomingLockId != null ? incomingLockId : UUID.randomUUID().toString());
        newLock.setLockInfo(body);
        newLock.setAcquiredAt(new Date());
        try {
            workspaceStateLockRepository.saveAndFlush(newLock);
        } catch (Exception e) {
            // race with concurrent LOCK on the same workspace: another instance won
            Optional<WorkspaceStateLock> winner = workspaceStateLockRepository.findById(wsUuid);
            if (winner.isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(winner.get().getLockInfo() != null ? winner.get().getLockInfo() : "{}");
            }
            throw e;
        }
        return ResponseEntity.ok("");
    }

    @DeleteMapping(value = "/organization/{organizationId}/workspace/{workspaceId}/lock")
    @Transactional
    public ResponseEntity<String> unlock(@PathVariable("organizationId") String organizationId,
                                         @PathVariable("workspaceId") String workspaceId,
                                         HttpServletRequest request) throws IOException {
        if (!isAuthorized(request)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String incomingLockId = readLockId(body);

        UUID wsUuid = UUID.fromString(workspaceId);
        Optional<WorkspaceStateLock> existing = workspaceStateLockRepository.findById(wsUuid);
        if (existing.isEmpty()) {
            return ResponseEntity.ok("");
        }
        WorkspaceStateLock lock = existing.get();
        // force-unlock (terraform force-unlock) sends no body or a different ID; honor it
        if (incomingLockId != null && lock.getLockId() != null && !lock.getLockId().equals(incomingLockId)) {
            log.warn("Unlock id mismatch on workspace {}: holder={}, requester={}", workspaceId, lock.getLockId(), incomingLockId);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(lock.getLockInfo() != null ? lock.getLockInfo() : "{}");
        }
        workspaceStateLockRepository.deleteById(wsUuid);
        return ResponseEntity.ok("");
    }

    private String readLockId(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return objectMapper.readTree(body).path("ID").asText(null);
        } catch (IOException e) {
            log.warn("Could not parse lock body as JSON: {}", e.getMessage());
            return null;
        }
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
            Jwts.parser().verifyWith(internalJwtKey).build().parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            log.warn("Rejected http-backend request: invalid token ({})", e.getMessage());
            return false;
        }
    }

}
