package io.terrakube.executor.service.workspace;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.job.Job;
import io.terrakube.client.model.organization.job.JobAttributes;
import io.terrakube.client.model.organization.job.step.Step;
import io.terrakube.client.model.response.ResponseWithInclude;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.FileUtils;
import org.apache.tools.tar.TarEntry;
import org.apache.tools.tar.TarOutputStream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.terrakube.executor.service.executor.ExecutorJobResult;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.terraform.TerraformExecutor;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;

public class SetupWorkspaceTest {
    @TempDir
    Path testHome;
    private String userHome;

    @BeforeEach
    public void setUp() throws IOException {
        userHome = System.getProperty("user.home");
        System.setProperty("user.home", testHome.toFile().getCanonicalPath());
    }

    @AfterEach
    public void tearDown() {
        System.setProperty("user.home", userHome);
    }

    private TerraformJob baseGitJob() {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("ze-org");
        job.setWorkspaceId("ze-ws");
        job.setFolder("executor/src/test/resources/terraform/hello-world");
        job.setVcsType("LOCAL");
        job.setConnectionType("");
        job.setAccessToken("");
        job.setEnvironmentVariables(new HashMap<String, String>());
        return job;
    }

    private TerraformJob successfulGitJob() throws Exception {
        TerraformJob job = baseGitJob();
        File sourceRepository = Files.createTempDirectory(testHome, "terrakube-source").toFile();
        try (Git sourceGit = Git.init().setDirectory(sourceRepository).call()) {
            commitFile(sourceGit, job.getFolder() + "/main.tf", "terraform");
            job.setSource(sourceRepository.toURI().toString());
            job.setBranch(sourceGit.getRepository().getBranch());
        }
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

    private SetupWorkspace standardSetupWorkspaceImpl(TerraformJob job) {
        String overrideSource = job != null && "remote-content".equals(job.getBranch()) ? job.getSource() : null;
        return new SetupWorkspaceImpl(new NoopWorkspaceSecurity(), false, new NoopTerraformExecutor(),
                "https://terrakube-api.example.com", terrakubeClient(overrideSource));
    }

    private static TerrakubeClient terrakubeClient(String overrideSource) {
        return (TerrakubeClient) Proxy.newProxyInstance(TerrakubeClient.class.getClassLoader(),
                new Class[] { TerrakubeClient.class }, (proxy, method, args) -> {
                    if (method.getDeclaringClass().equals(Object.class)) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> "TerrakubeClient test proxy";
                            default -> null;
                        };
                    }
                    if ("getJobById".equals(method.getName())) {
                        JobAttributes attributes = new JobAttributes();
                        attributes.setOverrideSource(overrideSource);
                        Job job = new Job();
                        job.setAttributes(attributes);
                        ResponseWithInclude<Job, Step> response = new ResponseWithInclude<>();
                        response.setData(job);
                        return response;
                    }
                    return null;
                });
    }

    private static class NoopWorkspaceSecurity implements WorkspaceSecurity {
        @Override
        public void addTerraformCredentials(String workspaceId) {
        }

        @Override
        public String generateAccessToken(String workspaceId) {
            return "test-token";
        }

        @Override
        public String generateAccessToken(int minutes) {
            return "test-token";
        }
    }

    private static class NoopTerraformExecutor implements TerraformExecutor {
        @Override
        public ExecutorJobResult plan(TerraformJob terraformJob, File workingDirectory, boolean isDestroy) {
            return null;
        }

        @Override
        public ExecutorJobResult apply(TerraformJob terraformJob, File workingDirectory) {
            return null;
        }

        @Override
        public ExecutorJobResult destroy(TerraformJob terraformJob, File workingDirectory) {
            return null;
        }

        @Override
        public String version() {
            return "";
        }
    }

    private TerraformJob localGitJob(File sourceRepository, String branch) {
        TerraformJob job = baseGitJob();
        job.setSource(sourceRepository.toURI().toString());
        job.setBranch(branch);
        return job;
    }

    private RevCommit commitFile(Git git, String fileName, String fileContent) throws Exception {
        File file = FileUtils.getFile(git.getRepository().getWorkTree(), fileName);
        FileUtils.forceMkdirParent(file);
        FileUtils.writeStringToFile(file, fileContent, StandardCharsets.UTF_8);
        git.add().addFilepattern(fileName).call();
        return git.commit()
                .setMessage(fileContent)
                .setAuthor("Terrakube Test", "test@example.com")
                .setCommitter("Terrakube Test", "test@example.com")
                .call();
    }

    private static class RejectingShaFetchSetupWorkspace extends SetupWorkspaceImpl {
        boolean unshallowRepositoryCalled;

        RejectingShaFetchSetupWorkspace() {
            super(new NoopWorkspaceSecurity(), false, new NoopTerraformExecutor(),
                    "https://terrakube-api.example.com", terrakubeClient(null));
        }

        @Override
        void fetchCommitById(Git git, File gitCloneFolder, TerraformJob terraformJob, String commitId)
                throws GitAPIException {
            throw new TransportException("SHA-in-want rejected");
        }

        @Override
        void unshallowRepository(Git git, File gitCloneFolder, TerraformJob terraformJob)
                throws GitAPIException, IOException {
            unshallowRepositoryCalled = true;
            super.unshallowRepository(git, gitCloneFolder, terraformJob);
        }
    }

    @Test
    public void downloadsAndChecksOutGitRepository() throws Exception {
        TerraformJob job = successfulGitJob();
        SetupWorkspace setup = standardSetupWorkspaceImpl(job);
        File workspaceDir = setup.prepareWorkspace(job);
        File terrformDir = FileUtils.getFile(workspaceDir, job.getFolder(), "main.tf");
        Assert.assertTrue(terrformDir.exists());
    }

    @Test
    public void performsShallowCloneWhenNoCommitIdRequested() throws Exception {
        TerraformJob job = successfulGitJob();
        SetupWorkspace setup = standardSetupWorkspaceImpl(job);
        File workspaceDir = setup.prepareWorkspace(job);
        File shallowMarker = FileUtils.getFile(workspaceDir, ".git", "shallow");
        Assert.assertTrue(shallowMarker.exists());
    }

    @Test
    public void checksOutRecordedCommitAfterSourceBranchAdvancesFromShallowClone() throws Exception {
        File sourceRepository = Files.createTempDirectory("terrakube-source").toFile();
        try (Git sourceGit = Git.init().setDirectory(sourceRepository).call()) {
            RevCommit plannedCommit = commitFile(sourceGit, "main.tf", "planned");
            String branch = sourceGit.getRepository().getBranch();

            TerraformJob planJob = localGitJob(sourceRepository, branch);
            SetupWorkspace setup = standardSetupWorkspaceImpl(planJob);
            File planWorkspaceDir = setup.prepareWorkspace(planJob);

            Assertions.assertTrue(FileUtils.getFile(planWorkspaceDir, ".git", "shallow").exists());
            Assertions.assertEquals(plannedCommit.getName(), FileUtils.readFileToString(
                    FileUtils.getFile(planWorkspaceDir, "commitHash.info"), Charset.defaultCharset()));

            commitFile(sourceGit, "main.tf", "advanced");

            TerraformJob applyJob = localGitJob(sourceRepository, branch);
            applyJob.setCommitId(plannedCommit.getName());
            RejectingShaFetchSetupWorkspace applySetup = new RejectingShaFetchSetupWorkspace();
            File applyWorkspaceDir = applySetup.prepareWorkspace(applyJob);

            Assertions.assertTrue(applySetup.unshallowRepositoryCalled);
            try (Git applyGit = Git.open(applyWorkspaceDir)) {
                Assertions.assertEquals(plannedCommit.getName(),
                        applyGit.getRepository().resolve("HEAD").getName());
            }
            Assertions.assertEquals("planned", FileUtils.readFileToString(
                    FileUtils.getFile(applyWorkspaceDir, "main.tf"), StandardCharsets.UTF_8));
            Assertions.assertEquals(plannedCommit.getName(), FileUtils.readFileToString(
                    FileUtils.getFile(applyWorkspaceDir, "commitHash.info"), Charset.defaultCharset()));
        } finally {
            FileUtils.deleteDirectory(sourceRepository);
        }
    }

    @Test
    public void keepsShallowCloneWhenRequestedCommitIsBranchTip() throws Exception {
        File sourceRepository = Files.createTempDirectory("terrakube-source").toFile();
        try (Git sourceGit = Git.init().setDirectory(sourceRepository).call()) {
            RevCommit branchTip = commitFile(sourceGit, "main.tf", "branch-tip");
            TerraformJob job = localGitJob(sourceRepository, sourceGit.getRepository().getBranch());
            job.setCommitId(branchTip.getName());

            RejectingShaFetchSetupWorkspace setup = new RejectingShaFetchSetupWorkspace();
            File workspaceDir = setup.prepareWorkspace(job);

            Assertions.assertFalse(setup.unshallowRepositoryCalled);
            Assertions.assertTrue(FileUtils.getFile(workspaceDir, ".git", "shallow").exists());
            try (Git workspaceGit = Git.open(workspaceDir)) {
                Assertions.assertEquals(branchTip.getName(), workspaceGit.getRepository().resolve("HEAD").getName());
            }
        } finally {
            FileUtils.deleteDirectory(sourceRepository);
        }
    }

    @Test
    public void injectsCommitHashInfo() throws Exception {
        TerraformJob job = successfulGitJob();
        SetupWorkspace setup = standardSetupWorkspaceImpl(job);
        File workspaceDir = setup.prepareWorkspace(job);
        File terrformDir = FileUtils.getFile(workspaceDir, "commitHash.info");
        Assert.assertTrue(terrformDir.exists());
    }

    @Test
    public void failsWhenAskedToCheckoutABadCommit() throws Exception {
        TerraformJob job = successfulGitJob();
        SetupWorkspace setup = standardSetupWorkspaceImpl(job);
        job.setCommitId("nonsense");
        WorkspaceException e = Assert.assertThrows(WorkspaceException.class, () -> setup.prepareWorkspace(job));
        Assert.assertEquals(RefNotFoundException.class, e.getCause().getClass());
    }

    @Test
    public void reportsFailureOnBadRepository() throws Exception {
        TerraformJob job = successfulGitJob();
        SetupWorkspace setup = standardSetupWorkspaceImpl(job);
        job.setSource("nonsense");
        WorkspaceException e = Assert.assertThrows(WorkspaceException.class, () -> setup.prepareWorkspace(job));
        Assert.assertEquals(InvalidRemoteException.class, e.getCause().getClass());
    }

    @Test
    public void downloadsAndUnpacksTarGz() throws Exception {
        TerraformJob job = successfulTarGzJob();
        SetupWorkspace setup = standardSetupWorkspaceImpl(job);
        File workspaceDir = setup.prepareWorkspace(job);
        File terrformDir = FileUtils.getFile(workspaceDir, "main.tf");
        Assert.assertTrue(terrformDir.exists());
    }

    @Test
    public void reportsFailureonBadTarGz() throws Exception {
        TerraformJob job = successfulTarGzJob();
        job.setSource("file:/nonsense");
        SetupWorkspace setup = standardSetupWorkspaceImpl(job);
        WorkspaceException e = Assert.assertThrows(WorkspaceException.class, () -> setup.prepareWorkspace(job));
        Assert.assertEquals(FileNotFoundException.class, e.getCause().getClass());
    }

    @Test
    public void injectsAwsCredentialsWhenAsked() throws Exception {
        TerraformJob job = successfulTarGzJob();
        SetupWorkspace setup = standardSetupWorkspaceImpl(job);
        job.getEnvironmentVariables().put("ENABLE_DYNAMIC_CREDENTIALS_AWS", "true");
        job.getEnvironmentVariables().put("TERRAKUBE_AWS_CREDENTIALS_FILE", "ze-secret");
        File workspaceDir = setup.prepareWorkspace(job);
        File credsFile = FileUtils.getFile(workspaceDir, "terrakube_config_dynamic_credentials_aws.txt");
        Assertions.assertTrue(credsFile.exists());
        Assertions.assertEquals("ze-secret", FileUtils.readFileToString(credsFile, Charset.defaultCharset()));
    }

    @Test
    public void injectsGcpCredentialsWhenAsked() throws Exception {
        TerraformJob job = successfulTarGzJob();
        SetupWorkspace setup = standardSetupWorkspaceImpl(job);
        job.getEnvironmentVariables().put("ENABLE_DYNAMIC_CREDENTIALS_GCP", "true");
        job.getEnvironmentVariables().put("TERRAKUBE_GCP_CREDENTIALS_FILE", "{\"access_token\":\"ze-jwt\"}");
        job.getEnvironmentVariables().put("TERRAKUBE_GCP_CREDENTIALS_CONFIG_FILE",
                "{\"credential_source\":{\"file\":\"${WORKSPACE_DIRECTORY}/terrakube_dynamic_credentials.json\"}}");

        File workspaceDir = setup.prepareWorkspace(job);
        File jwtFile = FileUtils.getFile(workspaceDir, "terrakube_dynamic_credentials.json");
        File configFile = FileUtils.getFile(workspaceDir, "terrakube_config_dynamic_credentials.json");

        Assertions.assertTrue(jwtFile.exists());
        Assertions.assertTrue(configFile.exists());
        Assertions.assertEquals("{\"access_token\":\"ze-jwt\"}", FileUtils.readFileToString(jwtFile, Charset.defaultCharset()));
        // ${WORKSPACE_DIRECTORY} placeholder must be substituted with the actual clone path.
        Assertions.assertEquals("{\"credential_source\":{\"file\":\"" + workspaceDir.getAbsolutePath()
                + "/terrakube_dynamic_credentials.json\"}}", FileUtils.readFileToString(configFile, Charset.defaultCharset()));
    }
}
