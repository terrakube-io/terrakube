package io.terrakube.executor.service.scripts.groovy;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroovyEngineSecurityTest {

    private TerrakubeClient terrakubeClient;
    private WorkspaceSecurity workspaceSecurity;
    private GroovyEngine groovyEngine;

    @TempDir
    File tempDir;

    @BeforeEach
    void setUp() {
        terrakubeClient = mock(TerrakubeClient.class);
        workspaceSecurity = mock(WorkspaceSecurity.class);
        when(workspaceSecurity.generateAccessToken(anyInt(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("mock-token");
        when(workspaceSecurity.generateAccessToken(anyInt(), anyString())).thenReturn("mock-token");
        groovyEngine = new GroovyEngine(terrakubeClient, workspaceSecurity, "http://localhost:8080");
    }

    private TerraformJob createTestJob() {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("org-123");
        job.setWorkspaceId("ws-123");
        job.setJobId("1");
        job.setStepId("step-1");
        job.setTerraformVersion("1.5.0");
        job.setSource("https://github.com/test/repo");
        job.setBranch("main");
        job.setEnvironmentVariables(new HashMap<>());
        job.setVariables(new HashMap<>());
        return job;
    }

    @Test
    void testSafeGroovyScriptExecutesSuccessfully() {
        String script = "terrakubeOutput.write('Hello from secure Groovy\\n'.bytes)";
        AtomicReference<String> outputRef = new AtomicReference<>();

        boolean result = groovyEngine.execute(createTestJob(), script, tempDir, outputRef::set);

        assertTrue(result, "Safe script execution should succeed");
        assertNotNull(outputRef.get());
        assertTrue(outputRef.get().contains("Hello from secure Groovy"));
    }

    @Test
    void testReflectionCannotAccessHostSecrets() {
        // Exploit payload from security review (TK-01 Bypass A)
        String script = "def c = Class.forName('java.lang.System')\n" +
                "def m = c.getMethod('getenv', [String.class] as Class[])\n" +
                "def secret = m.invoke(null, ['InternalSecret'] as Object[])\n" +
                "if (secret != null && !secret.toString().isBlank()) {\n" +
                "    throw new RuntimeException('EXPLOIT_SUCCESS_HOST_SECRET_LEAKED: ' + secret)\n" +
                "}\n" +
                "println 'REFLECTION_TEST_PASSED: InternalSecret is null'\n";
        AtomicReference<String> outputRef = new AtomicReference<>();

        boolean result = groovyEngine.execute(createTestJob(), script, tempDir, outputRef::set);

        assertTrue(result, "Script should complete without finding host secrets");
        assertNotNull(outputRef.get());
        assertTrue(outputRef.get().contains("REFLECTION_TEST_PASSED"), "Reflection must return null for host secret");
    }

    @Test
    void testHostSecretsAreNotPresentInChildProcessEnvironment() {
        String script = "def internalSecret = System.getenv('InternalSecret')\n" +
                "def redisPw = System.getenv('TerrakubeRedisPassword')\n" +
                "def awsKey = System.getenv('AwsTerraformStateAccessKey')\n" +
                "if (internalSecret != null || redisPw != null || awsKey != null) {\n" +
                "    throw new RuntimeException('HOST_SECRETS_LEAKED')\n" +
                "}\n" +
                "println 'ENVIRONMENT_ISOLATION_PASSED'\n";
        AtomicReference<String> outputRef = new AtomicReference<>();

        boolean result = groovyEngine.execute(createTestJob(), script, tempDir, outputRef::set);

        assertTrue(result, "Host secrets must be purged from child process environment");
        assertNotNull(outputRef.get());
        assertTrue(outputRef.get().contains("ENVIRONMENT_ISOLATION_PASSED"));
    }

    @Test
    void testChildGroovyProcessCannotAccessParentEnvironViaProcfs() {
        // Exploit payload from TK-01 review (round 2 procfs bypass):
        // def ppid = ProcessHandle.current().parent().get().pid()
        // def secret = new File("/proc/${ppid}/environ").text...
        String script = "def parentOpt = ProcessHandle.current().parent()\n" +
                "def ppid = parentOpt.isPresent() ? parentOpt.get().pid() : 0\n" +
                "if (ppid > 0) {\n" +
                "    File f = new File('/proc/' + ppid + '/environ')\n" +
                "    if (f.exists()) {\n" +
                "        def secret = f.text.split('\\\\u0000').find { it.startsWith('InternalSecret=') }\n" +
                "        if (secret != null) {\n" +
                "            throw new RuntimeException('EXPLOIT_SUCCESS_PARENT_PROCFS_LEAK: ' + secret)\n" +
                "        }\n" +
                "    }\n" +
                "}\n" +
                "println 'PROCFS_PARENT_ISOLATION_PASSED'\n";
        AtomicReference<String> outputRef = new AtomicReference<>();

        boolean result = groovyEngine.execute(createTestJob(), script, tempDir, outputRef::set);

        assertTrue(result, "Script should succeed without leaking parent environ");
        assertNotNull(outputRef.get());
        assertTrue(outputRef.get().contains("PROCFS_PARENT_ISOLATION_PASSED"));
    }

    @Test
    void testContextVariablesAndTokenAreAvailable() {
        String script = "assert workingDirectory != null\n" +
                "assert terrakubeApi == 'http://localhost:8080'\n" +
                "assert terrakubeToken == 'mock-token'\n" +
                "assert organizationId == 'org-123'\n" +
                "assert workspaceId == 'ws-123'\n" +
                "assert jobId == '1'\n" +
                "assert stepId == 'step-1'\n" +
                "println 'CONTEXT_VERIFICATION_PASSED'\n";
        AtomicReference<String> outputRef = new AtomicReference<>();

        boolean result = groovyEngine.execute(createTestJob(), script, tempDir, outputRef::set);

        assertTrue(result, "Context variables should be populated in child process bindings");
        assertNotNull(outputRef.get());
        assertTrue(outputRef.get().contains("CONTEXT_VERIFICATION_PASSED"));
    }

    @Test
    void testFailingGroovyScriptReturnsFalse() {
        String script = "throw new RuntimeException('Intentional failure')";
        AtomicReference<String> outputRef = new AtomicReference<>();

        boolean result = groovyEngine.execute(createTestJob(), script, tempDir, outputRef::set);

        assertFalse(result, "Failing script execution should return false");
    }

    @Test
    void testExtensionLoadingFromToolsRepository() throws Exception {
        File toolsRepoDir = new File(tempDir, ".terrakube/toolsRepository/CustomModule");
        assertTrue(toolsRepoDir.mkdirs());
        File extensionFile = new File(toolsRepoDir, "CustomHelper.groovy");
        org.apache.commons.io.FileUtils.writeStringToFile(
                extensionFile,
                "class CustomHelper {\n" +
                "    static String greet(String name) {\n" +
                "        return 'Hello ' + name\n" +
                "    }\n" +
                "}\n",
                java.nio.charset.StandardCharsets.UTF_8
        );

        String script = "import CustomHelper\n" +
                "println 'EXTENSION_LOADED: ' + CustomHelper.greet('Terrakube')\n";
        AtomicReference<String> outputRef = new AtomicReference<>();

        boolean result = groovyEngine.execute(createTestJob(), script, tempDir, outputRef::set);

        assertTrue(result, "Script importing custom extension should succeed");
        assertNotNull(outputRef.get());
        assertTrue(outputRef.get().contains("EXTENSION_LOADED: Hello Terrakube"),
                "Child process should dynamically load extension from toolsRepository");
    }

    @Test
    void testClasspathResolutionWithSpringBootLayout(@TempDir File mockAppDir) {
        File bootInfClasses = new File(mockAppDir, "BOOT-INF/classes");
        bootInfClasses.mkdirs();
        File bootInfLib = new File(mockAppDir, "BOOT-INF/lib");
        bootInfLib.mkdirs();

        String resolved = groovyEngine.resolveClasspathForGroovy(mockAppDir.getAbsolutePath());
        assertTrue(resolved.contains(bootInfClasses.getAbsolutePath()), "Should resolve BOOT-INF/classes");
        assertTrue(resolved.contains(bootInfLib.getAbsolutePath() + "/*"), "Should resolve BOOT-INF/lib/*");
    }
}
