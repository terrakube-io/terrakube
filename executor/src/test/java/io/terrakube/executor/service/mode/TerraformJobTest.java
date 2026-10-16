package io.terrakube.executor.service.mode;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerraformJobTest {

    @Test
    void legacyPayloadDoesNotRequireSavedPlan() throws Exception {
        TerraformJob job = new ObjectMapper().readValue("{\"type\":\"terraformApply\"}", TerraformJob.class);

        assertFalse(job.isRequireSavedPlan());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void readsRequireSavedPlanFromExecutorPayload(boolean required) throws Exception {
        TerraformJob job = new ObjectMapper().readValue(
                "{\"type\":\"terraformApply\",\"requireSavedPlan\":" + required + "}", TerraformJob.class);

        assertEquals(required, job.isRequireSavedPlan());
    }

    @Test
    void readsTheExecutionEpochAndDefaultsItToZeroForALegacyPayload() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        assertEquals(0, objectMapper.readValue("{\"type\":\"terraformPlan\"}", TerraformJob.class).getExecutionEpoch());
        assertEquals(1_760_371_234_567L, objectMapper.readValue(
                "{\"type\":\"terraformPlan\",\"executionEpoch\":1760371234567}", TerraformJob.class).getExecutionEpoch());
    }

    // BatchModeServiceImpl reads the payload with a plain ObjectMapper.
    @Test
    void ignoresAFieldANewerApiAdds() throws Exception {
        TerraformJob job = new ObjectMapper().readValue(
                "{\"type\":\"terraformPlan\",\"fieldFromANewerApi\":{\"a\":1}}", TerraformJob.class);

        assertEquals("terraformPlan", job.getType());
    }

    // OnlineModeServiceImpl logs the whole job at debug level, so toString() must not carry credentials,
    // variable values or outputs.
    @Test
    void toStringLeavesOutSecrets() {
        TerraformJob terraformJob = new TerraformJob();
        terraformJob.setJobId("42");
        terraformJob.setAccessToken("secret-access-token");
        terraformJob.setModuleSshKey("secret-ssh-key");
        terraformJob.setTerraformOutput("secret-output");
        HashMap<String, String> environmentVariables = new HashMap<>();
        environmentVariables.put("AWS_SECRET_ACCESS_KEY", "secret-env-value");
        terraformJob.setEnvironmentVariables(environmentVariables);
        HashMap<String, String> variables = new HashMap<>();
        variables.put("db_password", "secret-variable-value");
        terraformJob.setVariables(variables);
        terraformJob.setPolicyList(List.of(PolicyContext.builder()
                .policyName("policy")
                .accessToken("secret-policy-token")
                .moduleSshKey("secret-policy-ssh-key")
                .build()));

        String text = terraformJob.toString();

        assertTrue(text.contains("jobId=42"));
        assertTrue(text.contains("policyName=policy"));
        assertFalse(text.contains("secret"), text);
    }
}
