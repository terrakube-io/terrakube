package io.terrakube.executor.service.workspace;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.FileUtils;
import org.apache.tools.tar.TarEntry;
import org.apache.tools.tar.TarOutputStream;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.terraform.TerraformExecutor;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;

public class SetupWorkspaceTest {
    private TerraformJob successfulGitJob() {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("ze-org");
        job.setWorkspaceId("ze-ws");
        // TODO Restore actual values
        job.setSource("https://github.com/bittrance/terrakube");
        job.setBranch("executor-vcs-errors");
        job.setFolder("executor/src/test/resources/terraform/hello-world");
        job.setVcsType("GITHUB");
        job.setConnectionType("OAUTH");
        job.setAccessToken("ze-token");
        job.setEnvironmentVariables(new HashMap<String, String>());
        return job;
    }

    private URI terraformTarGz() throws IOException {
        File tgzFile = File.createTempFile("successfulTarGzJob", ".tar.gz");
        GzipCompressorOutputStream tgz = new GzipCompressorOutputStream(new FileOutputStream(tgzFile));
        try (TarOutputStream tar = new TarOutputStream(tgz)) {
            File tf = File.createTempFile("tfMain", ".tf");
            FileUtils.writeStringToFile(tf, "", StandardCharsets.US_ASCII, false);
            TarEntry tfEntry = new TarEntry(tf, "main.tf");
            tar.putNextEntry(tfEntry);
            tar.closeEntry();

            tf = File.createTempFile("tfVariables", ".tf");
            FileUtils.writeStringToFile(tf, "", StandardCharsets.US_ASCII, false);
            tfEntry = new TarEntry(tf, "variables.tf");
            tar.putNextEntry(tfEntry);
            tar.closeEntry();
            return tgzFile.toURI();
        }
    }

    private TerraformJob successfulTarGzJob() throws IOException {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("ze-org");
        job.setWorkspaceId("ze-ws");
        job.setSource(terraformTarGz().toString());
        job.setBranch("remote-content");
        job.setEnvironmentVariables(new HashMap<String, String>());
        return job;
    }

    private SetupWorkspace standardSetupWorkspaceImpl() {
        WorkspaceSecurity security = Mockito.mock(WorkspaceSecurity.class);
        TerraformExecutor executor = Mockito.mock(TerraformExecutor.class);
        return new SetupWorkspaceImpl(security, false, executor);
    }

    @Test
    public void downloadsAndChecksOutGitRepository() throws Exception {
        SetupWorkspace setup = standardSetupWorkspaceImpl();
        TerraformJob job = successfulGitJob();
        File workspaceDir = setup.prepareWorkspace(job);
        File terrformDir = FileUtils.getFile(workspaceDir, job.getFolder(), "main.tf");
        Assert.assertTrue(terrformDir.exists());
    }

    @Test
    public void injectsCommitHashInfo() throws Exception {
        SetupWorkspace setup = standardSetupWorkspaceImpl();
        TerraformJob job = successfulGitJob();
        File workspaceDir = setup.prepareWorkspace(job);
        File terrformDir = FileUtils.getFile(workspaceDir, "commitHash.info");
        Assert.assertTrue(terrformDir.exists());
    }

    @Test
    public void failsWhenAskedToCheckoutABadCommit() throws Exception {
        SetupWorkspace setup = standardSetupWorkspaceImpl();
        TerraformJob job = successfulGitJob();
        job.setCommitId("nonsense");
        WorkspaceException e = Assert.assertThrows(WorkspaceException.class, () -> setup.prepareWorkspace(job));
        Assert.assertEquals(RefNotFoundException.class, e.getCause().getClass());
    }

    @Test
    public void reportsFailureOnBadRepository() {
        SetupWorkspace setup = standardSetupWorkspaceImpl();
        TerraformJob job = successfulGitJob();
        job.setSource("nonsense");
        WorkspaceException e = Assert.assertThrows(WorkspaceException.class, () -> setup.prepareWorkspace(job));
        Assert.assertEquals(InvalidRemoteException.class, e.getCause().getClass());
    }

    @Test
    public void downloadsAndUnpacksTarGz() throws Exception {
        SetupWorkspace setup = standardSetupWorkspaceImpl();
        TerraformJob job = successfulTarGzJob();
        File workspaceDir = setup.prepareWorkspace(job);
        File terrformDir = FileUtils.getFile(workspaceDir, "main.tf");
        Assert.assertTrue(terrformDir.exists());
    }

    @Test
    public void reportsFailureonBadTarGz() throws Exception {
        SetupWorkspace setup = standardSetupWorkspaceImpl();
        TerraformJob job = successfulTarGzJob();
        job.setSource("file:/nonsense");
        WorkspaceException e = Assert.assertThrows(WorkspaceException.class, () -> setup.prepareWorkspace(job));
        Assert.assertEquals(FileNotFoundException.class, e.getCause().getClass());
    }

    @Test
    public void injectsAwsCredentialsWhenAsked() throws Exception {
        SetupWorkspace setup = standardSetupWorkspaceImpl();
        TerraformJob job = successfulTarGzJob();
        job.getEnvironmentVariables().put("ENABLE_DYNAMIC_CREDENTIALS_AWS", "true");
        job.getEnvironmentVariables().put("TERRAKUBE_AWS_CREDENTIALS_FILE", "ze-secret");
        File workspaceDir = setup.prepareWorkspace(job);
        File credsFile = FileUtils.getFile(workspaceDir, "terrakube_config_dynamic_credentials_aws.txt");
        Assert.assertTrue(credsFile.exists());
        Assert.assertEquals(FileUtils.readFileToString(credsFile, Charset.defaultCharset()), "ze-secret");
    }
}
