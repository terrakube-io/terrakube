package io.terrakube.api.plugin.module;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.terrakube.api.plugin.scheduler.module.ModuleRefreshTransactions;

@RestController
@RequestMapping("/module/v1")
public class ModuleSyncController {

    private final ModuleRefreshTransactions moduleRefreshTransactions;

    public ModuleSyncController(ModuleRefreshTransactions moduleRefreshTransactions) {
        this.moduleRefreshTransactions = moduleRefreshTransactions;
    }

    // Only marks the module due: the poller reads its tags on its next tick (within a minute). Accepted
    // whether or not a refresh is already running; a running one then writes nothing and is redone.
    @PostMapping("/organization/{orgId}/module/{moduleId}/sync")
    @PreAuthorize("@moduleAccessService.hasManagePermission(authentication, #orgId, #moduleId)")
    public ResponseEntity<Void> sync(@PathVariable String orgId, @PathVariable String moduleId) {
        moduleRefreshTransactions.markDue(UUID.fromString(moduleId));
        return ResponseEntity.accepted().build();
    }
}
