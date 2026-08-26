package io.terrakube.executor.service.workspace;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.job.Job;
import io.terrakube.client.model.organization.job.JobAttributes;
import io.terrakube.client.model.organization.job.step.Step;
import io.terrakube.client.model.response.ResponseWithInclude;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.FileUtils;
import org.apache.tools.tar.TarEntry;
import org.apache.tools.tar.TarOutputStream;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.junit.ssh.SshTestGitServer;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
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
            // no-op: this test double doesn't exercise credential setup
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

    private static List<String> refNames(Git git, String prefix) throws IOException {
        return git.getRepository().getRefDatabase().getRefsByPrefix(prefix).stream()
                .map(Ref::getName)
                .sorted()
                .toList();
    }

    private static class UnshallowRecordingSetupWorkspace extends SetupWorkspaceImpl {
        boolean unshallowRepositoryCalled;

        UnshallowRecordingSetupWorkspace() {
            super(new NoopWorkspaceSecurity(), false, new NoopTerraformExecutor(),
                    "https://terrakube-api.example.com", terrakubeClient(null));
        }

        @Override
        void unshallowRepository(Git git, File gitCloneFolder, TerraformJob terraformJob)
                throws GitAPIException, IOException {
            unshallowRepositoryCalled = true;
            super.unshallowRepository(git, gitCloneFolder, terraformJob);
        }
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
        Assertions.assertTrue(terrformDir.exists());
    }

    @Test
    public void performsShallowCloneWhenNoCommitIdRequested() throws Exception {
        TerraformJob job = successfulGitJob();
        SetupWorkspace setup = standardSetupWorkspaceImpl(job);
        File workspaceDir = setup.prepareWorkspace(job);
        File shallowMarker = FileUtils.getFile(workspaceDir, ".git", "shallow");
        Assertions.assertTrue(shallowMarker.exists());
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
    public void fetchesOnlyTheRequestedBranch() throws Exception {
        File sourceRepository = Files.createTempDirectory("terrakube-source").toFile();
        try (Git sourceGit = Git.init().setDirectory(sourceRepository).call()) {
            commitFile(sourceGit, "main.tf", "requested");
            String requestedBranch = sourceGit.getRepository().getBranch();

            // The tag sits on the unrelated branch so a narrowed fetch cannot reach it:
            // AUTO_FOLLOW would still pull a tag pointing at the requested branch's tip.
            sourceGit.checkout().setCreateBranch(true).setName("unrelated").call();
            commitFile(sourceGit, "main.tf", "unrelated");
            sourceGit.tag().setName("v1.0.0").call();
            sourceGit.checkout().setName(requestedBranch).call();

            TerraformJob job = localGitJob(sourceRepository, requestedBranch);
            File workspaceDir = standardSetupWorkspaceImpl(job).prepareWorkspace(job);

            try (Git workspaceGit = Git.open(workspaceDir)) {
                Assertions.assertEquals(List.of(Constants.R_REMOTES + "origin/" + requestedBranch),
                        refNames(workspaceGit, Constants.R_REMOTES));
                Assertions.assertEquals(List.of(), refNames(workspaceGit, Constants.R_TAGS));
            }
        } finally {
            FileUtils.deleteDirectory(sourceRepository);
        }
    }

    @Test
    public void checksOutWhenBranchFieldNamesATag() throws Exception {
        File sourceRepository = Files.createTempDirectory("terrakube-source").toFile();
        try (Git sourceGit = Git.init().setDirectory(sourceRepository).call()) {
            RevCommit tagged = commitFile(sourceGit, "main.tf", "tagged");
            sourceGit.tag().setName("v1.0.0").call();

            sourceGit.checkout().setCreateBranch(true).setName("unrelated").call();
            commitFile(sourceGit, "main.tf", "unrelated");

            TerraformJob job = localGitJob(sourceRepository, "v1.0.0");
            File workspaceDir = standardSetupWorkspaceImpl(job).prepareWorkspace(job);

            try (Git workspaceGit = Git.open(workspaceDir)) {
                Assertions.assertEquals(tagged.getName(),
                        workspaceGit.getRepository().resolve("HEAD").getName());
                Assertions.assertEquals(List.of(Constants.R_TAGS + "v1.0.0"),
                        refNames(workspaceGit, Constants.R_TAGS));
                Assertions.assertEquals(List.of(), refNames(workspaceGit, Constants.R_REMOTES));
            }
        } finally {
            FileUtils.deleteDirectory(sourceRepository);
        }
    }

    @Test
    public void checksOutARecordedCommitThatLivesOnAnotherBranch() throws Exception {
        File sourceRepository = Files.createTempDirectory("terrakube-source").toFile();
        try (Git sourceGit = Git.init().setDirectory(sourceRepository).call()) {
            commitFile(sourceGit, "main.tf", "requested");
            String requestedBranch = sourceGit.getRepository().getBranch();

            sourceGit.checkout().setCreateBranch(true).setName("unrelated").call();
            RevCommit unrelatedCommit = commitFile(sourceGit, "main.tf", "unrelated");
            sourceGit.checkout().setName(requestedBranch).call();

            TerraformJob job = localGitJob(sourceRepository, requestedBranch);
            job.setCommitId(unrelatedCommit.getName());

            UnshallowRecordingSetupWorkspace setup = new UnshallowRecordingSetupWorkspace();
            File workspaceDir = setup.prepareWorkspace(job);

            // The commit is an advertised ref tip, so the narrowed clone can fetch it by sha
            // rather than falling back to pulling the whole history.
            Assertions.assertFalse(setup.unshallowRepositoryCalled);
            Assertions.assertTrue(FileUtils.getFile(workspaceDir, ".git", "shallow").exists());
            try (Git workspaceGit = Git.open(workspaceDir)) {
                Assertions.assertEquals(unrelatedCommit.getName(),
                        workspaceGit.getRepository().resolve("HEAD").getName());
                Assertions.assertEquals(List.of(Constants.R_REMOTES + "origin/" + requestedBranch),
                        refNames(workspaceGit, Constants.R_REMOTES));
            }
        } finally {
            FileUtils.deleteDirectory(sourceRepository);
        }
    }

    @Test
    public void clonesOverSshAndLeavesTheKeyInTheWorkspace() throws Exception {
        File sourceRepository = Files.createTempDirectory("terrakube-source").toFile();
        KeyPair clientKey = generateRsaKeyPair();
        try (Git sourceGit = Git.init().setDirectory(sourceRepository).call()) {
            commitFile(sourceGit, "main.tf", "over-ssh");
            String requestedBranch = sourceGit.getRepository().getBranch();

            sourceGit.checkout().setCreateBranch(true).setName("unrelated").call();
            commitFile(sourceGit, "main.tf", "unrelated");
            sourceGit.checkout().setName(requestedBranch).call();

            SshTestGitServer server = new SshTestGitServer("git", clientKey.getPublic(),
                    sourceGit.getRepository(), generateRsaKeyPair());
            int port = server.start();
            try {
                TerraformJob job = sshJob(port, requestedBranch, clientKey);

                File workspaceDir = standardSetupWorkspaceImpl(job).prepareWorkspace(job);

                // TerraformExecutorServiceImpl.getSshFile reads the key back out of the
                // workspace, so the clone has to be what puts it there.
                Assertions.assertTrue(FileUtils.getFile(workspaceDir, ".ssh", "id_rsa").exists());
                Assertions.assertTrue(FileUtils.getFile(workspaceDir, ".git", "shallow").exists());
                try (Git workspaceGit = Git.open(workspaceDir)) {
                    Assertions.assertEquals(List.of(Constants.R_REMOTES + "origin/" + requestedBranch),
                            refNames(workspaceGit, Constants.R_REMOTES));
                }
                Assertions.assertEquals("over-ssh", FileUtils.readFileToString(
                        FileUtils.getFile(workspaceDir, "main.tf"), StandardCharsets.UTF_8));
            } finally {
                server.stop();
            }
        } finally {
            FileUtils.deleteDirectory(sourceRepository);
        }
    }

    @Test
    public void fetchesAMissingCommitOverSsh() throws Exception {
        File sourceRepository = Files.createTempDirectory("terrakube-source").toFile();
        KeyPair clientKey = generateRsaKeyPair();
        try (Git sourceGit = Git.init().setDirectory(sourceRepository).call()) {
            commitFile(sourceGit, "main.tf", "requested");
            String requestedBranch = sourceGit.getRepository().getBranch();

            sourceGit.checkout().setCreateBranch(true).setName("unrelated").call();
            RevCommit unrelatedCommit = commitFile(sourceGit, "main.tf", "unrelated");
            sourceGit.checkout().setName(requestedBranch).call();

            SshTestGitServer server = new SshTestGitServer("git", clientKey.getPublic(),
                    sourceGit.getRepository(), generateRsaKeyPair());
            int port = server.start();
            try {
                TerraformJob job = sshJob(port, requestedBranch, clientKey);
                job.setCommitId(unrelatedCommit.getName());

                UnshallowRecordingSetupWorkspace setup = new UnshallowRecordingSetupWorkspace();
                File workspaceDir = setup.prepareWorkspace(job);

                Assertions.assertFalse(setup.unshallowRepositoryCalled);
                Assertions.assertTrue(FileUtils.getFile(workspaceDir, ".git", "shallow").exists());
                try (Git workspaceGit = Git.open(workspaceDir)) {
                    Assertions.assertEquals(unrelatedCommit.getName(),
                            workspaceGit.getRepository().resolve("HEAD").getName());
                }
            } finally {
                server.stop();
            }
        } finally {
            FileUtils.deleteDirectory(sourceRepository);
        }
    }

    @Test
    public void unshallowsOverSshWhenTheShaFetchIsRejected() throws Exception {
        File sourceRepository = Files.createTempDirectory("terrakube-source").toFile();
        KeyPair clientKey = generateRsaKeyPair();
        try (Git sourceGit = Git.init().setDirectory(sourceRepository).call()) {
            RevCommit plannedCommit = commitFile(sourceGit, "main.tf", "planned");
            String requestedBranch = sourceGit.getRepository().getBranch();
            commitFile(sourceGit, "main.tf", "advanced");

            SshTestGitServer server = new SshTestGitServer("git", clientKey.getPublic(),
                    sourceGit.getRepository(), generateRsaKeyPair());
            int port = server.start();
            try {
                TerraformJob job = sshJob(port, requestedBranch, clientKey);
                job.setCommitId(plannedCommit.getName());

                RejectingShaFetchSetupWorkspace setup = new RejectingShaFetchSetupWorkspace();
                File workspaceDir = setup.prepareWorkspace(job);

                Assertions.assertTrue(setup.unshallowRepositoryCalled);
                try (Git workspaceGit = Git.open(workspaceDir)) {
                    Assertions.assertEquals(plannedCommit.getName(),
                            workspaceGit.getRepository().resolve("HEAD").getName());
                }
                Assertions.assertEquals("planned", FileUtils.readFileToString(
                        FileUtils.getFile(workspaceDir, "main.tf"), StandardCharsets.UTF_8));
            } finally {
                server.stop();
            }
        } finally {
            FileUtils.deleteDirectory(sourceRepository);
        }
    }

    private TerraformJob sshJob(int port, String branch, KeyPair clientKey) throws IOException {
        TerraformJob job = baseGitJob();
        job.setVcsType("SSH~id_rsa");
        job.setSource("ssh://git@localhost:" + port + "/terrakube-source");
        job.setBranch(branch);
        job.setAccessToken(privateKeyPem(clientKey));
        return job;
    }

    private static KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** PKCS#1, since that is the format the executor keys its file naming off. */
    private static String privateKeyPem(KeyPair keyPair) throws IOException {
        StringWriter pem = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(pem)) {
            writer.writeObject(keyPair.getPrivate());
        }
        return pem.toString();
    }

    @Test
    public void injectsCommitHashInfo() throws Exception {
        TerraformJob job = successfulGitJob();
        SetupWorkspace setup = standardSetupWorkspaceImpl(job);
        File workspaceDir = setup.prepareWorkspace(job);
        File terrformDir = FileUtils.getFile(workspaceDir, "commitHash.info");
        Assertions.assertTrue(terrformDir.exists());
    }

    @Test
    public void failsWhenAskedToCheckoutABadCommit() throws Exception {
        TerraformJob job = successfulGitJob();
        SetupWorkspace setup = standardSetupWorkspaceImpl(job);
        job.setCommitId("nonsense");
        WorkspaceException e = Assertions.assertThrows(WorkspaceException.class, () -> setup.prepareWorkspace(job));
        Assertions.assertEquals(RefNotFoundException.class, e.getCause().getClass());
    }

    @Test
    public void reportsFailureWhenTheBranchDoesNotExist() throws Exception {
        TerraformJob job = successfulGitJob();
        job.setBranch("does-not-exist");
        SetupWorkspace setup = standardSetupWorkspaceImpl(job);
        WorkspaceException e = Assertions.assertThrows(WorkspaceException.class, () -> setup.prepareWorkspace(job));
        Assertions.assertEquals(TransportException.class, e.getCause().getClass());
    }

    @Test
    public void reportsFailureOnBadRepository() throws Exception {
        TerraformJob job = successfulGitJob();
        SetupWorkspace setup = standardSetupWorkspaceImpl(job);
        job.setSource("nonsense");
        WorkspaceException e = Assertions.assertThrows(WorkspaceException.class, () -> setup.prepareWorkspace(job));
        Assertions.assertEquals(InvalidRemoteException.class, e.getCause().getClass());
    }

    @Test
    public void downloadsAndUnpacksTarGz() throws Exception {
        TerraformJob job = successfulTarGzJob();
        SetupWorkspace setup = standardSetupWorkspaceImpl(job);
        File workspaceDir = setup.prepareWorkspace(job);
        File terrformDir = FileUtils.getFile(workspaceDir, "main.tf");
        Assertions.assertTrue(terrformDir.exists());
    }

    @Test
    public void reportsFailureonBadTarGz() throws Exception {
        TerraformJob job = successfulTarGzJob();
        job.setSource("file:/nonsense");
        SetupWorkspace setup = standardSetupWorkspaceImpl(job);
        WorkspaceException e = Assertions.assertThrows(WorkspaceException.class, () -> setup.prepareWorkspace(job));
        Assertions.assertEquals(FileNotFoundException.class, e.getCause().getClass());
    }

    // Reproduces the "Run now" gap: a UI-created job on a remote-content workspace whose
    // job.overrideSource was never populated (see ExecutorService.persistJobOverrideSource on the
    // api side, which now prevents this from happening in practice). terrakubeClient(null) mirrors
    // that scenario without needing api-module code in this test.
    @Test
    public void reportsClearFailureWhenOverrideSourceIsMissing() throws Exception {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("ze-org");
        job.setWorkspaceId("ze-ws");
        job.setJobId("9041");
        job.setBranch("remote-content");
        job.setEnvironmentVariables(new HashMap<String, String>());
        SetupWorkspace setup = new SetupWorkspaceImpl(new NoopWorkspaceSecurity(), false, new NoopTerraformExecutor(),
                "https://terrakube-api.example.com", terrakubeClient(null));

        WorkspaceException e = Assertions.assertThrows(WorkspaceException.class, () -> setup.prepareWorkspace(job));

        Assertions.assertEquals(IOException.class, e.getCause().getClass());
        Assertions.assertTrue(e.getCause().getMessage().contains("9041"));
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
