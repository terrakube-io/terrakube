package io.terrakube.executor.service.mode;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerraformJobTest {

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
