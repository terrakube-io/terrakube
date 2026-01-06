package io.terrakube.api.plugin.importer.tfcloud.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.terrakube.api.plugin.importer.tfcloud.WorkspaceImportRequest;
import io.terrakube.api.plugin.importer.tfcloud.services.WorkspaceService;

@RestController
@RequestMapping("/importer/tfcloud")
@Slf4j
public class TfCloudController {

    private final WorkspaceService service;
    private static final String INVALID_URL_MESSAGE = "Invalid Importer URL, only approved URL are allowed please check with your Terrakube admin";

    public TfCloudController(WorkspaceService service) {
        this.service = service;
    }

    @Value("${io.terrakube.importer.allowedUrl}")
    private String allowedUrls;

    @GetMapping("/workspaces")
    public ResponseEntity<?> getWorkspaces(@RequestHeader("X-TFC-Url") String apiUrl,@RequestHeader("X-TFC-Token") String apiToken,
            @RequestParam String organization) {
        log.info("Allowed URLs getWorkspaces: {}", allowedUrls);
        String[] listUrls = this.allowedUrls.split(",");
        for(String url:listUrls){
            if(apiUrl.startsWith(url)){
                return ResponseEntity.ok(service.getWorkspaces(apiToken,apiUrl, organization));
            }
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(INVALID_URL_MESSAGE);

    }

    @PostMapping("/workspaces")
    public ResponseEntity<?> importWorkspaces(@RequestHeader("X-TFC-Url") String apiUrl,@RequestHeader("X-TFC-Token") String apiToken,@RequestBody WorkspaceImportRequest request) {
        log.info("Allowed URLs Import Workspaces: {}", allowedUrls);
        String[] listUrls = this.allowedUrls.split(",");
        for(String url:listUrls){
            if(apiUrl.startsWith(url)){
                String result = service.importWorkspace(apiToken,apiUrl,request);
                return ResponseEntity.ok().body(result);
            }
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(INVALID_URL_MESSAGE);
    }

}
