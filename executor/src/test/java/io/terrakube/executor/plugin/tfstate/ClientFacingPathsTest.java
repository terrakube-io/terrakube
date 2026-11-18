package io.terrakube.executor.plugin.tfstate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** URLs the executor stores for clients use the single-domain public URL when it is set. */
class ClientFacingPathsTest {

    private static TerraformOutputPathService output(String publicUrl) {
        TerraformOutputPathService service = new TerraformOutputPathService();
        ReflectionTestUtils.setField(service, "apiUrl", "http://terrakube-api-service:8080");
        ReflectionTestUtils.setField(service, "publicUrl", publicUrl);
        return service;
    }

    private static TerraformStatePathService state(String publicUrl) {
        TerraformStatePathService service = new TerraformStatePathService();
        ReflectionTestUtils.setField(service, "apiUrl", "http://terrakube-api-service:8080");
        ReflectionTestUtils.setField(service, "publicUrl", publicUrl);
        return service;
    }

    @Test
    void splitDomainKeepsTheApiUrl() {
        assertEquals("http://terrakube-api-service:8080/tfoutput/v1/organization/o/job/1/step/s",
                output("").getOutputPath("o", "1", "s"));
        assertEquals("http://terrakube-api-service:8080/tfstate/v1/organization/o/workspace/w/state/f.json",
                state("").getStateJsonPath("o", "w", "f"));
    }

    @Test
    void singleDomainHandsClientsThePublicApiBase() {
        assertEquals("https://app.example.com/api/tfoutput/v1/organization/o/job/1/step/s",
                output("https://app.example.com").getOutputPath("o", "1", "s"));
        assertEquals("https://app.example.com/api/tfstate/v1/organization/o/workspace/w/state/f.json",
                state("https://app.example.com").getStateJsonPath("o", "w", "f"));
    }

    @Test
    void httpBackendStaysOnTheApiUrl() {
        assertEquals("http://terrakube-api-service:8080/tfstate/v1/organization/o/workspace/w/backend/terraform.tfstate",
                state("https://app.example.com").getStateHttpBackendPath("o", "w"));
    }
}
