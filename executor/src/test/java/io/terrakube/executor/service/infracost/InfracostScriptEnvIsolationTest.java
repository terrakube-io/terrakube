package io.terrakube.executor.service.infracost;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.scripts.bash.BashEngine;
import io.terrakube.executor.service.scripts.groovy.GroovyEngine;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InfracostScriptEnvIsolationTest {

    @TempDir
    File tempDir;

    private WorkspaceSecurity workspaceSecurity;

    @BeforeEach
    void setUp() {
        workspaceSecurity = mock(WorkspaceSecurity.class);
        when(workspaceSecurity.generateAccessToken(anyInt(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("mock-token");
        when(workspaceSecurity.generateAccessToken(anyInt(), anyString())).thenReturn("mock-token");
    }

    @Test
    void testBashScriptCannotAccessInfracostSecrets() throws Exception {
        Files.writeString(tempDir.toPath().resolve(".terrakube_temp_env"),
                "INFRACOST_API_KEY=from-temp-env\nSAFE_TEMP_VAR=ok\n");

        TerraformJob job = baseJob();
        BashEngine bashEngine = new BashEngine(workspaceSecurity, "http://localhost:8080");

        StringBuilder out = new StringBuilder();
        String script = """
                echo "KEY=${INFRACOST_API_KEY:-UNSET}"
                echo "EP=${INFRACOST_PRICING_API_ENDPOINT:-UNSET}"
                echo "DASH=${INFRACOST_ENABLE_DASHBOARD:-UNSET}"
                echo "SAFE=${SAFE_TEMP_VAR:-UNSET}"
                echo "CUSTOM=${CUSTOM_VAR:-UNSET}"
                """;

        boolean success = bashEngine.execute(job, script, tempDir, line -> out.append(line).append('\n'));

        assertTrue(success, out.toString());
        String output = out.toString();
        assertTrue(output.contains("KEY=UNSET"), output);
        assertTrue(output.contains("EP=UNSET"), output);
        assertTrue(output.contains("DASH=UNSET"), output);
        assertTrue(output.contains("SAFE=ok"), output);
        assertTrue(output.contains("CUSTOM=allowed"), output);
        assertFalse(output.contains("ico-job-secret"));
    }

    @Test
    void testGroovyScriptCannotAccessInfracostSecrets() throws Exception {
        Files.writeString(tempDir.toPath().resolve(".terrakube_temp_env"),
                "INFRACOST_API_KEY=from-temp-env\n");

        TerraformJob job = baseJob();
        GroovyEngine groovyEngine = new GroovyEngine(mock(TerrakubeClient.class), workspaceSecurity, "http://localhost:8080");

        StringBuilder out = new StringBuilder();
        String script = """
                println "KEY=" + (System.getenv("INFRACOST_API_KEY") ?: "UNSET")
                println "EP=" + (System.getenv("INFRACOST_PRICING_API_ENDPOINT") ?: "UNSET")
                println "DASH=" + (System.getenv("INFRACOST_ENABLE_DASHBOARD") ?: "UNSET")
                println "CUSTOM=" + (System.getenv("CUSTOM_VAR") ?: "UNSET")
                """;

        boolean success = groovyEngine.execute(job, script, tempDir, line -> out.append(line).append('\n'));

        assertTrue(success, out.toString());
        String output = out.toString();
        assertTrue(output.contains("KEY=UNSET"), output);
        assertTrue(output.contains("EP=UNSET"), output);
        assertTrue(output.contains("DASH=UNSET"), output);
        assertTrue(output.contains("CUSTOM=allowed"), output);
        assertFalse(output.contains("ico-job-secret"));
    }

    private static TerraformJob baseJob() {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org-1");
        job.setWorkspaceId("ws-1");
        job.setJobId("101");
        job.setStepId("step-1");
        job.setTerraformVersion("1.5.0");
        job.setSource("https://github.com/example/repo");
        job.setBranch("main");

        HashMap<String, String> envVars = new HashMap<>();
        envVars.put("INFRACOST_API_KEY", "ico-job-secret");
        envVars.put("INFRACOST_PRICING_API_ENDPOINT", "https://attacker.example");
        envVars.put("CUSTOM_VAR", "allowed");
        job.setEnvironmentVariables(envVars);

        HashMap<String, String> tfVars = new HashMap<>();
        tfVars.put("INFRACOST_ENABLE_DASHBOARD", "true");
        job.setVariables(tfVars);
        return job;
    }
}
