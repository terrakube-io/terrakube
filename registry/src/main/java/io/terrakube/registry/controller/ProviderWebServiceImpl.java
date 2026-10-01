package io.terrakube.registry.controller;

import io.terrakube.registry.controller.model.provider.FileDTO;
import io.terrakube.registry.controller.model.provider.VersionsDTO;
import io.terrakube.registry.controller.model.provider.VersionDTO;
import io.terrakube.registry.service.provider.ProviderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/terraform/providers/v1")
public class ProviderWebServiceImpl {

    @Autowired
    ProviderService providerService;

    @GetMapping(value = "/{organization}/{provider}/versions", produces = "application/json")
    public ResponseEntity<VersionsDTO> searchModuleVersions(@PathVariable String organization, @PathVariable String provider) {
        List<VersionDTO> versionDTOList = providerService.getAvailableVersions(organization, provider);
        VersionsDTO versionsDTO = new VersionsDTO();
        versionsDTO.setVersions(versionDTOList);
        // Warnings are informational: failing to load them must never break terraform init (and is not cached).
        try {
            versionsDTO.setWarnings(providerService.getWarnings(organization, provider));
        } catch (RuntimeException e) {
            log.warn("Failed to load version warnings for provider {}/{}: {}", organization, provider, e.getMessage());
        }
        return ResponseEntity.ok(versionsDTO);
    }

    @GetMapping(value = "/{organization}/{provider}/{version}/download/{os}/{arch}", produces = "application/json")
    public ResponseEntity<FileDTO> getModuleVersionPath(@PathVariable String organization, @PathVariable String provider, @PathVariable String version, @PathVariable String os, @PathVariable String arch) {
        FileDTO fileDTO = providerService.getFileInformation(organization, provider, version, os, arch);
        if (fileDTO.getFilename() == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().body(fileDTO);
    }
}
