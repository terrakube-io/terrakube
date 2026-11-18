package io.terrakube.registry.controller;

import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.service.mirror.MirrorBusyException;
import io.terrakube.registry.service.mirror.MirrorUpstreamException;
import io.terrakube.registry.service.mirror.ProviderMirrorService;
import io.terrakube.registry.service.module.ModuleAuthorizationService;
import io.terrakube.registry.service.module.ModuleDownloadTicketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;

/** Provider Network Mirror Protocol, scoped to one organization. See docs/ops/registry-provider-mirror.md. */
@Slf4j
@RestController
@RequestMapping("/terraform/providers/mirror/v1/{organization}/{hostname}/{namespace}/{type}")
public class ProviderMirrorWebServiceImpl {

    private final ProviderMirrorService mirrorService;
    private final StorageService storageService;
    private final ModuleDownloadTicketService ticketService;
    private final ModuleAuthorizationService moduleAuthorizationService;

    public ProviderMirrorWebServiceImpl(ProviderMirrorService mirrorService, StorageService storageService,
            ModuleDownloadTicketService ticketService, ModuleAuthorizationService moduleAuthorizationService) {
        this.mirrorService = mirrorService;
        this.storageService = storageService;
        this.ticketService = ticketService;
        this.moduleAuthorizationService = moduleAuthorizationService;
    }

    @GetMapping(value = "/index.json", produces = "application/json")
    public ResponseEntity<String> index(@PathVariable String organization, @PathVariable String hostname,
            @PathVariable String namespace, @PathVariable String type, Authentication authentication) {
        if (!ProviderMirrorService.isValidProvider(hostname, namespace, type)) {
            return ResponseEntity.badRequest().build();
        }
        if (!isAuthorized(authentication, organization)) {
            log.warn("Unauthorized provider mirror index request for {}/{}/{}/{}", organization, hostname, namespace, type);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return json(mirrorService.index(hostname, namespace, type), CacheControl.noCache().cachePrivate());
    }

    @GetMapping(value = "/{version}.json", produces = "application/json")
    public ResponseEntity<String> version(@PathVariable String organization, @PathVariable String hostname,
            @PathVariable String namespace, @PathVariable String type, @PathVariable String version,
            Authentication authentication) {
        if (!ProviderMirrorService.isValidProvider(hostname, namespace, type) || !ProviderMirrorService.isValidVersion(version)) {
            return ResponseEntity.badRequest().build();
        }
        if (!isAuthorized(authentication, organization)) {
            log.warn("Unauthorized provider mirror version request for {}/{}/{}/{}", organization, hostname, namespace, type);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        // no-store: the archive URLs carry short-lived tickets.
        return json(mirrorService.version(organization, hostname, namespace, type, version), CacheControl.noStore());
    }

    /** Public in the security chain because clients send no credentials here; needs a ticket or an authorized token. */
    @GetMapping("/{version}/{platform}.zip")
    public ResponseEntity<Resource> archive(@PathVariable String organization, @PathVariable String hostname,
            @PathVariable String namespace, @PathVariable String type, @PathVariable String version,
            @PathVariable String platform, @RequestParam(name = "ticket", required = false) String ticket,
            Authentication authentication) {
        if (!ProviderMirrorService.isValidProvider(hostname, namespace, type) || !ProviderMirrorService.isValidVersion(version)
                || !ProviderMirrorService.isValidPlatform(platform)) {
            return ResponseEntity.badRequest().build();
        }
        boolean allowed = ticket != null && !ticket.isBlank()
                ? ticketService.validateMirrorTicket(ticket, organization, hostname, namespace, type, version, platform)
                : isAuthorized(authentication, organization);
        if (!allowed) {
            log.warn("Rejected provider mirror archive request for {}/{}/{}/{}/{}/{}", organization, hostname, namespace,
                    type, version, platform);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Optional<String> key = mirrorService.archiveKey(hostname, namespace, type, version, platform);
        if (key.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<URI> presigned = storageService.presignObject(key.get());
        if (presigned.isPresent()) {
            return ResponseEntity.status(HttpStatus.FOUND).location(presigned.get()).cacheControl(CacheControl.noStore()).build();
        }
        return storageService.readObject(key.get())
                .<ResponseEntity<Resource>>map(in -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .contentType(MediaType.parseMediaType("application/zip")).body(new InputStreamResource(in)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(MirrorUpstreamException.class)
    public ResponseEntity<Void> upstreamFailed(MirrorUpstreamException exception) {
        log.warn("Provider mirror upstream failure: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }

    @ExceptionHandler(MirrorBusyException.class)
    public ResponseEntity<Void> busy(MirrorBusyException exception) {
        log.warn("Provider mirror busy: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "10").build();
    }

    private boolean isAuthorized(Authentication authentication, String organization) {
        return moduleAuthorizationService.isAuthorized(authentication, organization);
    }

    private static ResponseEntity<String> json(Optional<String> body, CacheControl cacheControl) {
        return body.map(value -> ResponseEntity.ok().cacheControl(cacheControl).body(value))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
