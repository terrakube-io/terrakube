package io.terrakube.api.plugin.migrate;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/migrate/v1/")
@AllArgsConstructor
public class MigrateController {

    private final MigrateService migrateService;

    @Transactional
    @PreAuthorize("@migrateAccessService.hasMigrationPermission(authentication, #workspaceId, #organizationId)")
    @PostMapping(produces = "application/json", path = "/workspace/{workspaceId}/moveTo/{organizationId}")
    public ResponseEntity<String> migrateWorkspace(@PathVariable("workspaceId") String workspaceId, @PathVariable("organizationId") String organizationId) {
        boolean success = migrateService.migrateWorkspace(workspaceId, organizationId);
        if (success) {
            return ResponseEntity.status(HttpStatus.OK).body("{\"status\":\"success\"}");
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"status\":\"error\",\"message\":\"Migration failed. Workspace may be locked, deleted, or target organization invalid.\"}");
        }
    }
}
