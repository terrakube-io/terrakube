package io.terrakube.registry.controller;

import io.terrakube.registry.controller.model.module.ModuleDTO;
import io.terrakube.registry.controller.model.module.ModuleDetailsDTO;
import io.terrakube.registry.controller.model.module.VersionDTO;
import io.terrakube.registry.controller.model.module.VersionsDTO;
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.service.inspect.ModuleInspectorService;
import io.terrakube.registry.service.module.ModuleAuthorizationService;
import io.terrakube.registry.service.module.ModuleDownloadTicketService;
import io.terrakube.registry.service.module.ModuleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/terraform/modules/v1")
public class ModuleWebServiceImpl {

    @Autowired
    ModuleService moduleService;

    @Autowired
    StorageService storageService;

    @Autowired(required = false)
    ModuleDownloadTicketService ticketService;

    @Autowired(required = false)
    ModuleAuthorizationService moduleAuthorizationService;

    @Autowired
    ModuleInspectorService moduleInspectorService;

    public ModuleWebServiceImpl() {}

    public ModuleWebServiceImpl(ModuleService moduleService, StorageService storageService,
                                ModuleDownloadTicketService ticketService,
                                ModuleAuthorizationService moduleAuthorizationService) {
        this(moduleService, storageService, ticketService, moduleAuthorizationService, null);
    }

    public ModuleWebServiceImpl(ModuleService moduleService, StorageService storageService,
                                ModuleDownloadTicketService ticketService,
                                ModuleAuthorizationService moduleAuthorizationService,
                                ModuleInspectorService moduleInspectorService) {
        this.moduleService = moduleService;
        this.storageService = storageService;
        this.ticketService = ticketService;
        this.moduleAuthorizationService = moduleAuthorizationService;
        this.moduleInspectorService = moduleInspectorService;
    }

    @GetMapping(value = "/{organization}/{module}/{provider}/versions", produces = "application/json")
    public ResponseEntity<ModuleDTO> searchModuleVersions(
            @PathVariable String organization,
            @PathVariable String module,
            @PathVariable String provider,
            Authentication authentication) {
        if (moduleAuthorizationService == null || !moduleAuthorizationService.isAuthorized(authentication, organization)) {
            log.warn("Unauthorized access to module versions for {}/{}/{}", organization, module, provider);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        VersionsDTO versionsDTO = new VersionsDTO();
        List<VersionDTO> versionDTOList = new ArrayList<>();
        for (String availableVersion : moduleService.getAvailableVersions(organization, module, provider)) {
            VersionDTO version = new VersionDTO();
            version.setVersion(availableVersion);

            versionDTOList.add(version);
        }
        versionsDTO.setVersions(versionDTOList);
        ModuleDTO moduleDTO = new ModuleDTO();
        moduleDTO.setModules(Arrays.asList(versionsDTO));
        return ResponseEntity.ok(moduleDTO);
    }

    @GetMapping(value = "/{organization}/{module}/{provider}/{version}/download", produces = "application/json")
    public ResponseEntity<ModuleDTO> getModuleVersionPath(
            @PathVariable String organization,
            @PathVariable String module,
            @PathVariable String provider,
            @PathVariable String version,
            Authentication authentication) {
        if (moduleAuthorizationService == null || !moduleAuthorizationService.isAuthorized(authentication, organization)) {
            log.warn("Unauthorized access to module download path for {}/{}/{}/{}", organization, module, provider, version);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (moduleService.isVersionRemoved(organization, module, provider, version)) {
            // Terraform picked it from this replica's cached list; refresh the list so the next run picks another.
            moduleService.evictAvailableVersions(organization, module, provider);
            return ResponseEntity.notFound().build();
        }

        String downloadPath = moduleService.getModuleVersionPath(organization, module, provider, version);
        if (ticketService != null) {
            String ticket = ticketService.generateTicket(organization, module, provider, version);
            downloadPath = downloadPath.contains("?") ?
                    downloadPath + "&ticket=" + ticket :
                    downloadPath + "?ticket=" + ticket;
        }

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.set("X-Terraform-Get", downloadPath);
        responseHeaders.set("Access-Control-Expose-Headers", "X-Terraform-Get");
        moduleService.updateModuleDownloadCount(organization, module, provider);
        return ResponseEntity.noContent().headers(responseHeaders).build();
    }

    /** Inputs, outputs, resources and submodules of one module version, parsed server-side. */
    @GetMapping(value = "/{organization}/{module}/{provider}/{version}/details", produces = "application/json")
    public ResponseEntity<ModuleDetailsDTO> getModuleDetails(@PathVariable String organization, @PathVariable String module, @PathVariable String provider, @PathVariable String version,
                                                             @RequestParam(required = false, defaultValue = "") String submodule,
                                                             Authentication authentication) {
        if (moduleAuthorizationService == null || !moduleAuthorizationService.isAuthorized(authentication, organization)) {
            log.warn("Unauthorized access to module details for {}/{}/{}/{}", organization, module, provider, version);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (moduleService.isVersionRemoved(organization, module, provider, version)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(moduleInspectorService.details(organization, module, provider, version, submodule));
    }

    @GetMapping(value = "/download/{organizationName}/{moduleName}/{providerName}/{version}/module.zip")
    public ResponseEntity<byte[]> getModuleZip(
            @PathVariable String organizationName,
            @PathVariable String moduleName,
            @PathVariable String providerName,
            @PathVariable String version,
            @RequestParam(name = "ticket", required = false) String ticket,
            Authentication authentication) {

        if (ticket != null && !ticket.isBlank()) {
            if (ticketService == null || !ticketService.validateTicket(ticket, organizationName, moduleName, providerName, version)) {
                log.warn("Invalid, expired, or version-mismatched download ticket for {}/{}/{}/{}",
                        organizationName, moduleName, providerName, version);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        } else {
            if (moduleAuthorizationService == null || !moduleAuthorizationService.isAuthorized(authentication, organizationName)) {
                log.warn("Unauthorized direct module download attempt for {}/{}/{}/{}",
                        organizationName, moduleName, providerName, version);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        }

        if (moduleService.isVersionRemoved(organizationName, moduleName, providerName, version)) {
            // Terraform picked it from this replica's cached list; refresh the list so the next run picks another.
            moduleService.evictAvailableVersions(organizationName, moduleName, providerName);
            return ResponseEntity.notFound().build();
        }
        Optional<URI> presignedDownloadUrl = storageService.getPresignedDownloadUrl(organizationName, moduleName, providerName, version);
        if (presignedDownloadUrl.isPresent()) {
            return ResponseEntity.status(HttpStatus.FOUND).location(presignedDownloadUrl.get()).build();
        }

        byte[] data = storageService.downloadModule(organizationName, moduleName, providerName, version);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(data);
    }
}

