package io.terrakube.api.plugin.migrate;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/migrate/v1/")
@AllArgsConstructor
public class MigrateController {

    @Autowired
    MigrateService migrateService;

    @Transactional
    @PreAuthorize("@schedulerReconciliationAccessService.isAdmin(authentication)")
    @PostMapping(produces = "application/json", path = "/workspace/{workspaceId}/moveTo/{organizationId}")
    public ResponseEntity<String> migrateWorkspace(@PathVariable("workspaceId") String workspaceId, @PathVariable("organizationId") String organizationId) {
        migrateService.migrateWorkspace(workspaceId, organizationId);
        return ResponseEntity.status(200).body("");
    }
}
