package io.terrakube.api.plugin.proxy;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/proxy/v1")
public class ProxyController {

    private final ProxyService proxyService;

    public ProxyController(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @PreAuthorize("@proxyAccessService.hasProxyPermission(authentication, #workspaceId)")
    @GetMapping("/**")
    public ResponseEntity<String> proxyGetRequest(RequestEntity<String> requestEntity, @RequestParam("targetUrl") String targetUrl, @RequestParam(value = "proxyheaders", required = false) String proxyHeaders, @RequestParam("workspaceId") UUID workspaceId) {
        return proxyService.proxyRequest(requestEntity, targetUrl, proxyHeaders, workspaceId);
    }

    @PreAuthorize("@proxyAccessService.hasProxyPermission(authentication, #workspaceId)")
    @PostMapping(value = "/**", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_FORM_URLENCODED_VALUE})
    public ResponseEntity<String> proxyPostRequest(RequestEntity<String> requestEntity, @RequestParam("targetUrl") String targetUrl, @RequestParam(value = "proxyheaders", required = false) String proxyHeaders, @RequestParam("workspaceId") UUID workspaceId) {
        return proxyService.proxyRequest(requestEntity, targetUrl, proxyHeaders, workspaceId);
    }

    @PreAuthorize("@proxyAccessService.hasProxyPermission(authentication, #workspaceId)")
    @PutMapping("/**")
    public ResponseEntity<String> proxyPutRequest(RequestEntity<String> requestEntity, @RequestParam("targetUrl") String targetUrl, @RequestParam(value = "proxyheaders", required = false) String proxyHeaders, @RequestParam("workspaceId") UUID workspaceId) {
        return proxyService.proxyRequest(requestEntity, targetUrl, proxyHeaders, workspaceId);
    }

    @PreAuthorize("@proxyAccessService.hasProxyPermission(authentication, #workspaceId)")
    @DeleteMapping("/**")
    public ResponseEntity<String> proxyDeleteRequest(RequestEntity<String> requestEntity, @RequestParam("targetUrl") String targetUrl, @RequestParam(value = "proxyheaders", required = false) String proxyHeaders, @RequestParam("workspaceId") UUID workspaceId) {
        return proxyService.proxyRequest(requestEntity, targetUrl, proxyHeaders, workspaceId);
    }

    @PreAuthorize("@proxyAccessService.hasProxyPermission(authentication, #workspaceId)")
    @PatchMapping("/**")
    public ResponseEntity<String> proxyPatchRequest(RequestEntity<String> requestEntity, @RequestParam("targetUrl") String targetUrl, @RequestParam(value = "proxyheaders", required = false) String proxyHeaders, @RequestParam("workspaceId") UUID workspaceId) {
        return proxyService.proxyRequest(requestEntity, targetUrl, proxyHeaders, workspaceId);
    }
}