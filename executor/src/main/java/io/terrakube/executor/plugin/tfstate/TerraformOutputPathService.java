package io.terrakube.executor.plugin.tfstate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TerraformOutputPathService {

    private static final String GENERIC_OUTPUT_PATH = "%s/tfoutput/v1/organization/%s/job/%s/step/%s";

    @Value("${io.terrakube.api.url}")
    private String apiUrl;

    // Single-domain public URL. Output URLs are handed to the UI and other clients, so they use
    // <public>/api instead of the executor's (possibly internal) API URL.
    @Value("${io.terrakube.public-url:}")
    private String publicUrl;

    public String getOutputPath(String organizationId, String jobId, String stepId){
        String base = publicUrl == null || publicUrl.isBlank() ? this.apiUrl : publicUrl + "/api";
        return String.format(GENERIC_OUTPUT_PATH, base, organizationId, jobId, stepId);
    }
}
