package io.terrakube.executor.plugin.tfstate.http;

import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.service.logs.StepOutputBuffer;
import io.terrakube.executor.service.logs.StepOutputPartWriter;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HttpTerraformStateImplTest {

    private static final String ORG_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String WS_ID = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";
    private static final String JOB_ID = "42";
    private static final String STEP_ID = "0714492b-0f6c-4a0d-b77b-1522b5b24212";

    @Mock
    private TerraformStatePathService terraformStatePathService;

    @Mock
    private WorkspaceSecurity workspaceSecurity;

    @Mock
    private TerraformState delegate;

    private HttpTerraformStateImpl httpTerraformState;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        httpTerraformState = HttpTerraformStateImpl.builder()
                .terraformStatePathService(terraformStatePathService)
                .workspaceSecurity(workspaceSecurity)
                .delegate(delegate)
                .build();
    }

    @Test
    void testGetBackendStateFileWithJobAndStepGeneratesScopedHttpBackendBlockWithoutEmbeddingCredentials()
            throws Exception {
        String expectedUrl = "https://terrakube-api.localhost/tfstate/v1/organization/" + ORG_ID + "/workspace/" + WS_ID
                + "/backend/terraform.tfstate";

        when(terraformStatePathService.getStateHttpBackendPath(ORG_ID, WS_ID)).thenReturn(expectedUrl);

        File workingDir = tempDir.toFile();
        String fileName = httpTerraformState.getBackendStateFile(ORG_ID, WS_ID, JOB_ID, STEP_ID, workingDir, "1.6.0");

        assertEquals("terrakube_override.tf", fileName);
        File overrideFile = new File(workingDir, "terrakube_override.tf");
        assertTrue(overrideFile.exists());

        String content = FileUtils.readFileToString(overrideFile, StandardCharsets.UTF_8);
        assertTrue(content.contains("backend \"http\" {"));
        assertTrue(content.contains("address  = \"" + expectedUrl + "\""));
        // Credentials must not be in the HCL file to avoid baking them into .tfplan
        // binary plan files
        assertFalse(content.contains("username"));
        assertFalse(content.contains("password"));
        assertFalse(content.contains("access_key"));
        assertFalse(content.contains("secret_key"));
        assertFalse(content.contains("storage_account_name"));
        assertFalse(content.contains("GCP_CREDENTIALS_FILE.json"));
    }

    @Test
    void testGetBackendEnvironmentVariablesProvidesBasicAuthCredentials() {
        String expectedToken = "scoped.jwt.token.value";
        when(workspaceSecurity.generateAccessToken(60, ORG_ID, WS_ID, JOB_ID, STEP_ID)).thenReturn(expectedToken);

        Map<String, String> env = httpTerraformState.getBackendEnvironmentVariables(ORG_ID, WS_ID, JOB_ID, STEP_ID);

        assertNotNull(env);
        assertEquals("terraform", env.get("TF_HTTP_USERNAME"));
        assertEquals(expectedToken, env.get("TF_HTTP_PASSWORD"));
    }

    @Test
    void testGetBackendEnvironmentVariablesWithoutJobIdGeneratesScopedTokenWithNullJobAndStep() {
        String expectedToken = "fallback.jwt.token";
        when(workspaceSecurity.generateAccessToken(60, ORG_ID, WS_ID, null, null)).thenReturn(expectedToken);

        Map<String, String> env = httpTerraformState.getBackendEnvironmentVariables(ORG_ID, WS_ID, null, null);

        assertNotNull(env);
        assertEquals("terraform", env.get("TF_HTTP_USERNAME"));
        assertEquals(expectedToken, env.get("TF_HTTP_PASSWORD"));
    }

    @Test
    void testGetBackendEnvironmentVariablesGeneratesTokenDirectlyWithoutCaching() {
        when(workspaceSecurity.generateAccessToken(60, ORG_ID, WS_ID, JOB_ID, STEP_ID))
                .thenReturn("first.token")
                .thenReturn("second.token");

        Map<String, String> env1 = httpTerraformState.getBackendEnvironmentVariables(ORG_ID, WS_ID, JOB_ID, STEP_ID);
        Map<String, String> env2 = httpTerraformState.getBackendEnvironmentVariables(ORG_ID, WS_ID, JOB_ID, STEP_ID);

        assertEquals("first.token", env1.get("TF_HTTP_PASSWORD"));
        assertEquals("second.token", env2.get("TF_HTTP_PASSWORD"));
        verify(workspaceSecurity, times(2)).generateAccessToken(60, ORG_ID, WS_ID, JOB_ID, STEP_ID);
    }

    @Test
    void testGetBackendEnvironmentVariablesRespectsConfiguredTtl() {
        HttpTerraformStateImpl customTtlState = HttpTerraformStateImpl.builder()
                .terraformStatePathService(terraformStatePathService)
                .workspaceSecurity(workspaceSecurity)
                .delegate(delegate)
                .tokenTtlMinutes(120)
                .build();

        String expectedToken = "custom.ttl.token";
        when(workspaceSecurity.generateAccessToken(120, ORG_ID, WS_ID, JOB_ID, STEP_ID)).thenReturn(expectedToken);

        Map<String, String> env = customTtlState.getBackendEnvironmentVariables(ORG_ID, WS_ID, JOB_ID, STEP_ID);

        assertEquals("terraform", env.get("TF_HTTP_USERNAME"));
        assertEquals(expectedToken, env.get("TF_HTTP_PASSWORD"));
        verify(workspaceSecurity, times(1)).generateAccessToken(120, ORG_ID, WS_ID, JOB_ID, STEP_ID);
    }

    @Test
    void testDelegatesStorageOperationsToUnderlyingImplementation() throws Exception {
        File workingDir = tempDir.toFile();
        TerraformJob job = new TerraformJob();
        StepOutputBuffer buffer = new StepOutputBuffer();
        StepOutputPartWriter.PartSource partSource = org.mockito.Mockito.mock(StepOutputPartWriter.PartSource.class);
        byte[] patchBytes = new byte[] { 1, 2, 3 };

        when(delegate.saveTerraformPlan(ORG_ID, WS_ID, JOB_ID, STEP_ID, workingDir)).thenReturn("plan-url");
        when(delegate.downloadTerraformPlan(ORG_ID, WS_ID, JOB_ID, STEP_ID, workingDir)).thenReturn(true);
        when(delegate.saveArtifacts(ORG_ID, WS_ID, JOB_ID, STEP_ID, workingDir)).thenReturn("sha256");
        when(delegate.downloadArtifacts(ORG_ID, WS_ID, JOB_ID, workingDir)).thenReturn("sha256");
        when(delegate.saveBufferedOutput(ORG_ID, JOB_ID, STEP_ID, buffer, "err")).thenReturn("output-url");
        when(delegate.saveOutputPart(ORG_ID, JOB_ID, STEP_ID, 1, partSource)).thenReturn(true);
        when(delegate.saveTerraformBinary("1.6.0", false, workingDir)).thenReturn(true);
        when(delegate.downloadTerraformBinary("1.6.0", false, workingDir)).thenReturn(true);
        when(delegate.saveOpaBinary("0.60.0", "linux", "amd64", workingDir)).thenReturn(true);
        when(delegate.downloadOpaBinary("0.60.0", "linux", "amd64", workingDir)).thenReturn(true);
        when(delegate.spoolContextPatch(ORG_ID, JOB_ID, STEP_ID, "post", patchBytes)).thenReturn("spool-key");

        assertEquals("plan-url", httpTerraformState.saveTerraformPlan(ORG_ID, WS_ID, JOB_ID, STEP_ID, workingDir));
        assertTrue(httpTerraformState.downloadTerraformPlan(ORG_ID, WS_ID, JOB_ID, STEP_ID, workingDir));
        assertEquals("sha256", httpTerraformState.saveArtifacts(ORG_ID, WS_ID, JOB_ID, STEP_ID, workingDir));
        assertEquals("sha256", httpTerraformState.downloadArtifacts(ORG_ID, WS_ID, JOB_ID, workingDir));
        httpTerraformState.deleteArtifacts(ORG_ID, WS_ID, JOB_ID);
        verify(delegate).deleteArtifacts(ORG_ID, WS_ID, JOB_ID);

        httpTerraformState.saveStateJson(job, "{}", "{}");
        verify(delegate).saveStateJson(job, "{}", "{}");

        assertEquals("output-url", httpTerraformState.saveBufferedOutput(ORG_ID, JOB_ID, STEP_ID, buffer, "err"));
        assertTrue(httpTerraformState.saveOutputPart(ORG_ID, JOB_ID, STEP_ID, 1, partSource));
        httpTerraformState.deleteOutputParts(ORG_ID, JOB_ID, STEP_ID);
        verify(delegate).deleteOutputParts(ORG_ID, JOB_ID, STEP_ID);

        assertTrue(httpTerraformState.saveTerraformBinary("1.6.0", false, workingDir));
        assertTrue(httpTerraformState.downloadTerraformBinary("1.6.0", false, workingDir));
        assertTrue(httpTerraformState.saveOpaBinary("0.60.0", "linux", "amd64", workingDir));
        assertTrue(httpTerraformState.downloadOpaBinary("0.60.0", "linux", "amd64", workingDir));
        assertEquals("spool-key", httpTerraformState.spoolContextPatch(ORG_ID, JOB_ID, STEP_ID, "post", patchBytes));
        assertNotNull(httpTerraformState.getDelegate());
    }
}
