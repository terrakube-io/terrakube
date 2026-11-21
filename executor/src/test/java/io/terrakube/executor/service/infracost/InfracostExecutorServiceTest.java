package io.terrakube.executor.service.infracost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.configuration.InfracostExecutorProperties;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.terraform.JobContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InfracostExecutorServiceTest {

    @TempDir
    Path tempDir;

    private InfracostBinaryService binaryService;
    private InfracostExecutorProperties properties;
    private JobContextService jobContextService;
    private ObjectMapper objectMapper;
    private Map<String, String> systemEnv;
    private InfracostExecutorServiceImpl service;

    @BeforeEach
    void setUp() {
        binaryService = Mockito.mock(InfracostBinaryService.class);
        properties = new InfracostExecutorProperties();
        jobContextService = Mockito.mock(JobContextService.class);
        objectMapper = new ObjectMapper();
        systemEnv = new HashMap<>();
        systemEnv.put("PATH", System.getenv("PATH"));

        service = new InfracostExecutorServiceImpl(
                binaryService,
                properties,
                jobContextService,
                objectMapper,
                systemEnv::get
        );
    }

    @Test
    void testSkippedWhenApiKeyMissing() throws IOException {
        File workDir = tempDir.toFile();
        File planJson = Files.writeString(tempDir.resolve("plan.json"), "{}").toFile();
        TerraformJob job = baseJob();

        InfracostResult result = service.evaluateCost(job, workDir, planJson);

        assertEquals(InfracostResult.Status.SKIPPED, result.getStatus());
        verifyNoInteractions(binaryService, jobContextService);
    }

    @Test
    void testSkippedWhenExplicitlyDisabled() throws IOException {
        File workDir = tempDir.toFile();
        File planJson = Files.writeString(tempDir.resolve("plan.json"), "{}").toFile();
        TerraformJob job = baseJob();
        job.getEnvironmentVariables().put("INFRACOST_API_KEY", "ico-secret123456");
        job.getEnvironmentVariables().put("INFRACOST_ENABLED", "false");

        InfracostResult result = service.evaluateCost(job, workDir, planJson);

        assertEquals(InfracostResult.Status.SKIPPED, result.getStatus());
        verifyNoInteractions(binaryService, jobContextService);
    }

    @Test
    void testBuildCommandExclusivityBetweenPathAndConfigFile() throws IOException {
        File bin = tempDir.resolve("infracost").toFile();
        File planJson = tempDir.resolve("plan.json").toFile();
        File usageFile = tempDir.resolve("infracost-usage.yml").toFile();
        File configFile = tempDir.resolve("infracost.yml").toFile();
        File outFile = tempDir.resolve("out.json").toFile();

        List<String> pathCmd = service.buildCommand(bin, planJson, null, usageFile, outFile);
        assertTrue(pathCmd.contains("--path"));
        assertTrue(pathCmd.contains("--usage-file"));
        assertFalse(pathCmd.contains("--config-file"));

        List<String> configCmd = service.buildCommand(bin, planJson, configFile, usageFile, outFile);
        assertTrue(configCmd.contains("--config-file"));
        assertFalse(configCmd.contains("--path"));
        assertFalse(configCmd.contains("--usage-file"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testEvaluateCostSuccess_PersistsContextAndOpaData() throws Exception {
        File workDir = tempDir.toFile();
        File planJson = Files.writeString(tempDir.resolve("plan.json"), "{}").toFile();
        Files.writeString(tempDir.resolve("infracost-usage.yml"), "version: 0.1\n");

        File fakeBin = writeFakeInfracostScript("""
                #!/bin/sh
                set -eu
                if [ "${INFRACOST_API_KEY:-}" != "ico-real-test-key" ]; then
                  echo "missing api key" >&2
                  exit 2
                fi
                if [ "${INFRACOST_ENABLE_DASHBOARD:-}" != "false" ]; then
                  echo "dashboard must default to false" >&2
                  exit 3
                fi
                OUT=""
                SAW_USAGE="false"
                while [ "$#" -gt 0 ]; do
                  case "$1" in
                    --out-file) OUT="$2"; shift 2 ;;
                    --usage-file) SAW_USAGE="true"; shift 2 ;;
                    *) shift ;;
                  esac
                done
                if [ "$SAW_USAGE" != "true" ]; then
                  echo "expected auto-detected usage file" >&2
                  exit 4
                fi
                cat > "$OUT" <<'JSON'
                {
                  "version": "0.2",
                  "currency": "USD",
                  "totalMonthlyCost": "165.20",
                  "pastTotalMonthlyCost": "120.00",
                  "diffTotalMonthlyCost": "45.20",
                  "summary": {
                    "totalDetectedResources": 3,
                    "totalSupportedResources": 2,
                    "totalUnsupportedResources": 1,
                    "totalNoPriceResources": 0
                  },
                  "projects": [{"name": "demo", "breakdown": {"resources": []}}]
                }
                JSON
                """);

        when(binaryService.getInfracostBinary(null)).thenReturn(fakeBin);

        TerraformJob job = baseJob();
        job.getEnvironmentVariables().put("INFRACOST_API_KEY", "ico-real-test-key");

        InfracostResult result = service.evaluateCost(job, workDir, planJson);

        assertEquals(InfracostResult.Status.SUCCESS, result.getStatus());
        assertNotNull(result.getInfracostOpaDataFile());
        assertTrue(result.getInfracostOpaDataFile().isFile());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(jobContextService).patchContextFresh(eq("org-1"), eq("100"), captor.capture());
        assertTrue(captor.getValue().containsKey("infracost"));

        JsonNode opaNode = objectMapper.readTree(result.getInfracostOpaDataFile()).path("infracost");
        assertEquals("SUCCESS", opaNode.path("status").asText());
        assertEquals("165.20", opaNode.path("totalMonthlyCost").asText());
        assertEquals("45.20", opaNode.path("diffTotalMonthlyCost").asText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testEvaluateCostFailOpenAndRedactsApiKeyOnCliFailure() throws Exception {
        File workDir = tempDir.toFile();
        File planJson = Files.writeString(tempDir.resolve("plan.json"), "{}").toFile();
        String secret = "ico-super-secret-value";

        File fakeBin = writeFakeInfracostScript("""
                #!/bin/sh
                echo "Authentication failed for token $INFRACOST_API_KEY" >&2
                exit 1
                """);
        when(binaryService.getInfracostBinary(null)).thenReturn(fakeBin);

        TerraformJob job = baseJob();
        job.getEnvironmentVariables().put("INFRACOST_API_KEY", secret);

        InfracostResult result = service.evaluateCost(job, workDir, planJson);

        assertEquals(InfracostResult.Status.ERROR, result.getStatus());
        assertNotNull(result.getErrorMessage());
        assertFalse(result.getErrorMessage().contains(secret));
        assertTrue(result.getErrorMessage().contains("[REDACTED]"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(jobContextService).patchContextFresh(eq("org-1"), eq("100"), captor.capture());
        Map<String, Object> infracostMap = (Map<String, Object>) captor.getValue().get("infracost");
        assertEquals("ERROR", infracostMap.get("status"));
        assertFalse(String.valueOf(infracostMap.get("errorMessage")).contains(secret));
    }

    @Test
    void testEvaluateCostFailOpenOnTimeout() throws Exception {
        properties.setTimeoutSeconds(1L);
        File workDir = tempDir.toFile();
        File planJson = Files.writeString(tempDir.resolve("plan.json"), "{}").toFile();

        File fakeBin = writeFakeInfracostScript("""
                #!/bin/sh
                sleep 10
                """);
        when(binaryService.getInfracostBinary(null)).thenReturn(fakeBin);

        TerraformJob job = baseJob();
        job.getEnvironmentVariables().put("INFRACOST_API_KEY", "ico-timeout-key-999");

        InfracostResult result = service.evaluateCost(job, workDir, planJson);

        assertEquals(InfracostResult.Status.ERROR, result.getStatus());
        assertTrue(result.getErrorMessage().contains("timed out"));
    }

    @Test
    void testEvaluateCostRejectsPathTraversalFailOpen() throws Exception {
        File workDir = Files.createDirectory(tempDir.resolve("workspace")).toFile();
        File planJson = Files.writeString(workDir.toPath().resolve("plan.json"), "{}").toFile();

        TerraformJob job = baseJob();
        job.getEnvironmentVariables().put("INFRACOST_API_KEY", "ico-traversal-key-123");
        job.getEnvironmentVariables().put("INFRACOST_CONFIG_FILE", "../outside-secret.yml");

        InfracostResult result = service.evaluateCost(job, workDir, planJson);

        assertEquals(InfracostResult.Status.ERROR, result.getStatus());
        assertTrue(result.getErrorMessage().contains("escapes workspace directory"));
        verifyNoInteractions(binaryService);
    }

    private File writeFakeInfracostScript(String scriptContent) throws IOException {
        File script = tempDir.resolve("fake-infracost.sh").toFile();
        Files.writeString(script.toPath(), scriptContent);
        assertTrue(script.setExecutable(true, true));
        return script;
    }

    private static TerraformJob baseJob() {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org-1");
        job.setWorkspaceId("ws-1");
        job.setJobId("100");
        job.setStepId("step-1");
        job.setEnvironmentVariables(new HashMap<>());
        return job;
    }
}
