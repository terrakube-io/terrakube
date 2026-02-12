package io.terrakube.api.plugin.proxy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/registry/v1")
public class PublicRegistryProxyController {

    private final PublicRegistryProxyService publicRegistryProxyService;

    public PublicRegistryProxyController(PublicRegistryProxyService publicRegistryProxyService) {
        this.publicRegistryProxyService = publicRegistryProxyService;
    }

    /**
     * Search providers in Terraform Registry
     * GET /registry/v1/providers?q={query}&limit={limit}
     */
    @GetMapping("/providers")
    public ResponseEntity<String> searchProviders(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return publicRegistryProxyService.searchProviders(query, limit);
    }

    /**
     * Search modules in Terraform Registry
     * GET /registry/v1/modules?q={query}&limit={limit}
     */
    @GetMapping("/modules")
    public ResponseEntity<String> searchModules(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return publicRegistryProxyService.searchModules(query, limit);
    }

    /**
     * Get provider versions from Terraform Registry
     * GET /registry/v1/providers/{namespace}/{name}/versions
     */
    @GetMapping("/providers/{namespace}/{name}/versions")
    public ResponseEntity<String> getProviderVersions(
            @PathVariable("namespace") String namespace,
            @PathVariable("name") String name) {
        return publicRegistryProxyService.getProviderVersions(namespace, name);
    }

    /**
     * Get provider download info from Terraform Registry
     * GET /registry/v1/providers/{namespace}/{name}/{version}/download/{os}/{arch}
     */
    @GetMapping("/providers/{namespace}/{name}/{version}/download/{os}/{arch}")
    public ResponseEntity<String> getProviderDownload(
            @PathVariable("namespace") String namespace,
            @PathVariable("name") String name,
            @PathVariable("version") String version,
            @PathVariable("os") String os,
            @PathVariable("arch") String arch) {
        return publicRegistryProxyService.getProviderDownload(namespace, name, version, os, arch);
    }

}
