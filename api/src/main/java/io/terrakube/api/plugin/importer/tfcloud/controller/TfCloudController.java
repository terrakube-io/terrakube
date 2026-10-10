package io.terrakube.api.plugin.importer.tfcloud.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.terrakube.api.plugin.importer.tfcloud.WorkspaceImport;
import io.terrakube.api.plugin.importer.tfcloud.WorkspaceImportRequest;
import io.terrakube.api.plugin.importer.tfcloud.services.WorkspaceService;
import org.springframework.web.client.HttpClientErrorException;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/importer/tfcloud")
@Slf4j
public class TfCloudController {

    private final WorkspaceService service;
    private static final String INVALID_URL_MESSAGE = "Invalid Importer URL, only approved URL are allowed please check with your Terrakube admin";
    private static final String RATE_LIMIT_MESSAGE = "Terraform Cloud API rate limit exceeded. Please wait a moment and try again.";

    public TfCloudController(WorkspaceService service) {
        this.service = service;
    }

    @Value("${io.terrakube.importer.allowedUrl}")
    private String allowedUrls;

    @GetMapping("/workspaces")
    public ResponseEntity<List<WorkspaceImport.WorkspaceData>> getWorkspaces(@RequestHeader("X-TFC-Url") String apiUrl,@RequestHeader("X-TFC-Token") String apiToken,
            @RequestParam String organization) {
        log.info("Allowed URLs getWorkspaces: {}", allowedUrls);
        if (isAllowedImporterUrl(apiUrl)) {
            return ResponseEntity.ok(service.getWorkspaces(apiToken, apiUrl, organization));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ArrayList<>());
    }

    @GetMapping("/workspaces/{workspaceId}/varsets")
    public ResponseEntity<?> getWorkspaceVarsets(@RequestHeader("X-TFC-Url") String apiUrl,
            @RequestHeader("X-TFC-Token") String apiToken,
            @PathVariable String workspaceId) {
        log.info("Allowed URLs getWorkspaceVarsets: {}", allowedUrls);
        if (isAllowedImporterUrl(apiUrl)) {
            return ResponseEntity.ok(service.getWorkspaceVarsets(apiToken, apiUrl, workspaceId));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(INVALID_URL_MESSAGE);
    }

    @GetMapping("/workspaces/{workspaceId}/sensitive-variables")
    public ResponseEntity<?> getWorkspaceSensitiveVariables(@RequestHeader("X-TFC-Url") String apiUrl,
            @RequestHeader("X-TFC-Token") String apiToken,
            @PathVariable String workspaceId) {
        log.info("Allowed URLs getWorkspaceSensitiveVariables: {}", allowedUrls);
        if (isAllowedImporterUrl(apiUrl)) {
            return ResponseEntity.ok(service.getSensitiveVariables(apiToken, apiUrl, workspaceId));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(INVALID_URL_MESSAGE);
    }

    @PostMapping("/workspaces")
    public ResponseEntity<String> importWorkspaces(@RequestHeader("X-TFC-Url") String apiUrl,@RequestHeader("X-TFC-Token") String apiToken,@RequestBody WorkspaceImportRequest request) {
        log.info("Allowed URLs Import Workspaces: {}", allowedUrls);
        if (isAllowedImporterUrl(apiUrl)) {
            String result = service.importWorkspace(apiToken, apiUrl, request);
            return ResponseEntity.ok().body(result);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(INVALID_URL_MESSAGE);
    }

    boolean isAllowedImporterUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return false;
        }

        URI apiUri;
        try {
            apiUri = new URI(apiUrl);
        } catch (URISyntaxException e) {
            log.warn("Invalid apiUrl URI syntax: {}", apiUrl);
            return false;
        }

        String scheme = apiUri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            log.warn("Invalid scheme in apiUrl: {}", scheme);
            return false;
        }

        if (apiUri.getUserInfo() != null) {
            log.warn("Userinfo component detected in apiUrl, rejecting");
            return false;
        }

        String host = apiUri.getHost();
        if (host == null || host.isBlank()) {
            log.warn("No host found in apiUrl: {}", apiUrl);
            return false;
        }

        if (this.allowedUrls == null || this.allowedUrls.isBlank()) {
            return false;
        }

        boolean matchedAllowlist = false;
        String[] listUrls = this.allowedUrls.split(",");
        for (String allowedUrl : listUrls) {
            String trimmedAllowed = allowedUrl.trim();
            if (trimmedAllowed.isEmpty()) {
                continue;
            }
            try {
                URI allowedUri = new URI(trimmedAllowed);
                if (!scheme.equalsIgnoreCase(allowedUri.getScheme())) {
                    continue;
                }
                if (!host.equalsIgnoreCase(allowedUri.getHost())) {
                    continue;
                }

                int apiPort = apiUri.getPort() != -1 ? apiUri.getPort() : ("https".equalsIgnoreCase(scheme) ? 443 : 80);
                int allowedPort = allowedUri.getPort() != -1 ? allowedUri.getPort() : ("https".equalsIgnoreCase(allowedUri.getScheme()) ? 443 : 80);
                if (apiPort != allowedPort) {
                    continue;
                }

                String allowedPath = allowedUri.getPath();
                String apiPath = apiUri.getPath() != null ? apiUri.getPath() : "";
                if (allowedPath != null && !allowedPath.isBlank() && !allowedPath.equals("/")) {
                    String normalizedAllowedPath = allowedPath.endsWith("/") ? allowedPath : allowedPath + "/";
                    String normalizedApiPath = apiPath.endsWith("/") ? apiPath : apiPath + "/";
                    if (!normalizedApiPath.startsWith(normalizedAllowedPath)) {
                        continue;
                    }
                }

                matchedAllowlist = true;
                break;
            } catch (URISyntaxException e) {
                log.warn("Invalid URI syntax in configured allowedUrl: {}", trimmedAllowed);
            }
        }

        if (!matchedAllowlist) {
            log.warn("apiUrl {} did not match any allowed URLs in {}", apiUrl, allowedUrls);
            return false;
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
                    log.warn("apiUrl host {} resolves to blocked IP address {}", host, addr.getHostAddress());
                    return false;
                }
                byte[] bytes = addr.getAddress();
                if (addr instanceof Inet4Address && bytes.length == 4) {
                    int first = bytes[0] & 0xFF;
                    int second = bytes[1] & 0xFF;
                    if (first == 169 && second == 254) {
                        log.warn("apiUrl host {} resolves to cloud metadata address {}", host, addr.getHostAddress());
                        return false;
                    }
                }
            }
        } catch (UnknownHostException e) {
            log.warn("Unable to resolve host for apiUrl: {}", host);
            return false;
        }

        return true;
    }

    @ExceptionHandler(HttpClientErrorException.TooManyRequests.class)
    public ResponseEntity<String> handleTooManyRequests(HttpClientErrorException.TooManyRequests exception) {
        HttpHeaders headers = new HttpHeaders();
        HttpHeaders responseHeaders = exception.getResponseHeaders();
        if (responseHeaders != null) {
            String retryAfter = responseHeaders.getFirst(HttpHeaders.RETRY_AFTER);
            if (retryAfter != null && !retryAfter.isBlank()) {
                headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
            }
        }

        log.warn("Terraform Cloud importer request hit a rate limit: {}", exception.getMessage());
        return new ResponseEntity<>(RATE_LIMIT_MESSAGE, headers, HttpStatus.TOO_MANY_REQUESTS);
    }

}
