package io.terrakube.executor.plugin.tfstate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TerraformStatePathService {

    //{{server}}/tfstate/v1/organization/{{organizationId}}/workspace/{{workspaceId}}/state/{{stateFilename}}.json
    private static final String GENERIC_JSON_PATH = "%s/tfstate/v1/organization/%s/workspace/%s/state/%s.json";

    //{{server}}/tfstate/v1/organization/{{organizationId}}/workspace/{{workspaceId}}/jobId/{{jobId}}/step/{{stepIdBinary}}/terraform.tfstate
    //{{server}}/tfstate/v1/organization/{{organizationId}}/workspace/{{workspaceId}}/jobId/{{jobId}}/step/{{stepIdBinary}}/terraform.tfstate
    private static final String GENERIC_STATE_BINARY_PATH = "%s/tfstate/v1/organization/%s/workspace/%s/jobId/%s/step/%s/terraform.tfstate";

    //{{server}}/tfstate/v1/organization/{{organizationId}}/workspace/{{workspaceId}}/backend/terraform.tfstate
    private static final String GENERIC_HTTP_BACKEND_PATH = "%s/tfstate/v1/organization/%s/workspace/%s/backend/terraform.tfstate";

    @Value("${io.terrakube.api.url}")
    private String apiUrl;

    // Single-domain public URL. State JSON URLs are shown to clients, so they use <public>/api; the
    // HTTP backend address is used by Terraform inside the executor and stays on the API URL.
    @Value("${io.terrakube.public-url:}")
    private String publicUrl;

    private String clientBaseUrl() {
        return publicUrl == null || publicUrl.isBlank() ? this.apiUrl : publicUrl + "/api";
    }

    public String getStateJsonPath(String organizationId, String workspaceId, String stateFilename) {
        String path = String.format(GENERIC_JSON_PATH, clientBaseUrl(), organizationId, workspaceId, stateFilename);
        log.info("File State Json: {}", path);
        return path;
    }

    public String getTerraformBinaryPlanPath(String organizationId, String workspaceId, String jobId, String stepId) {
        log.info("File Binary State: {}", String.format(GENERIC_STATE_BINARY_PATH, this.apiUrl, organizationId, workspaceId, jobId, stepId));
        return String.format(GENERIC_STATE_BINARY_PATH, this.apiUrl, organizationId, workspaceId, jobId, stepId);
    }

    public String getStateHttpBackendPath(String organizationId, String workspaceId) {
        log.info("HTTP Backend State URL: {}", String.format(GENERIC_HTTP_BACKEND_PATH, this.apiUrl, organizationId, workspaceId));
        return String.format(GENERIC_HTTP_BACKEND_PATH, this.apiUrl, organizationId, workspaceId);
    }
}
