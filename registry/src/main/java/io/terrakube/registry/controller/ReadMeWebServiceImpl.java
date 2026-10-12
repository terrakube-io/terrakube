package io.terrakube.registry.controller;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.registry.controller.model.ReadMe;
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.service.module.ModuleAuthorizationService;
import io.terrakube.registry.service.module.ModuleService;
import io.terrakube.registry.service.ReadMeServiceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;

@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("/terraform/readme/v1")
public class ReadMeWebServiceImpl {

    ModuleService moduleService;
    ReadMeServiceImpl readMeService;
    StorageService storageService;
    ModuleAuthorizationService moduleAuthorizationService;

    @GetMapping(value = "/{organization}/{module}/{provider}/{version}/download", produces = "application/json")
    public ResponseEntity<ReadMe> getModuleVersionPath(
            @PathVariable String organization,
            @PathVariable String module,
            @PathVariable String provider,
            @PathVariable String version,
            Authentication authentication) {
        if (moduleAuthorizationService == null || !moduleAuthorizationService.isAuthorized(authentication, organization)) {
            log.warn("Unauthorized access to module readme for {}/{}/{}", organization, module, provider);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (moduleService.isVersionRemoved(organization, module, provider, version)) {
            // Terraform picked it from this replica's cached list; refresh the list so the next run picks another.
            moduleService.evictAvailableVersions(organization, module, provider);
            return ResponseEntity.notFound().build();
        }
        ReadMe readMe = new ReadMe();
        String moduleURL = moduleService.getModuleVersionPath(organization, module, provider, version);
        readMe.setUrl(moduleURL);
        readMe.setContent(readMeService.getContent(new ByteArrayInputStream(storageService.downloadModule(organization, module, provider, version))));
        return ResponseEntity.ok().body(readMe);
    }
}
