package io.terrakube.executor.service.workspace;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.job.Job;
import io.terrakube.client.model.organization.job.JobAttributes;
import io.terrakube.client.model.organization.job.step.Step;
import io.terrakube.client.model.response.ResponseWithInclude;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.FileUtils;
import org.apache.tools.tar.TarEntry;
import org.apache.tools.tar.TarOutputStream;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
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
import org.junit.jupiter.api.Assumptions;
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

    private URI executableScriptTarGz() throws IOException {
        File tgzFile = File.createTempFile("executableScriptTarGz", ".tar.gz");
        GzipCompressorOutputStream tgz = new GzipCompressorOutputStream(new FileOutputStream(tgzFile));
        try (TarOutputStream tar = new TarOutputStream(tgz)) {
            File tf = File.createTempFile("tfMain", ".tf");
            FileUtils.writeStringToFile(tf, "", StandardCharsets.US_ASCII, false);
            TarEntry tfEntry = new TarEntry(tf, "main.tf");
            tfEntry.setMode(0100644);
            tar.putNextEntry(tfEntry);
            tar.closeEntry();

            File script = File.createTempFile("script", ".sh");
            FileUtils.writeStringToFile(script, "", StandardCharsets.US_ASCII, false);
            TarEntry scriptEntry = new TarEntry(script, "scripts/run.sh");
            scriptEntry.setMode(0100755);
            tar.putNextEntry(scriptEntry);
            tar.closeEntry();
            return tgzFile.toURI();
        }
    }

    private SetupWorkspace standardSetupWorkspaceImpl(TerraformJob job) {
        String overrideSource = job != null && "remote-content".equals(job.getBranch()) ? job.getSource() : null;
        return new SetupWorkspaceImpl(new NoopWorkspaceSecurity(), false, new NoopTerraformExecutor(),
                "https://terrakube-api.example.com", terrakubeClient(overrideSource), new TarGzArchiver());
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

        @Override
        public void installBinary(TerraformJob terraformJob) {
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
                    "https://terrakube-api.example.com", terrakubeClient(null), new TarGzArchiver());
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
                    "https://terrakube-api.example.com", terrakubeClient(null), new TarGzArchiver());
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

    // A workspace that restricts apply to its configured branch sends the branch the commit must be on.

    @Test
    public void refusesACommitThatIsNotOnTheRequiredBranch() throws Exception {
        File sourceRepository = Files.createTempDirectory("terrakube-source").toFile();
        try (Git sourceGit = Git.init().setDirectory(sourceRepository).call()) {
            commitFile(sourceGit, "main.tf", "requested");
            String requestedBranch = sourceGit.getRepository().getBranch();
            sourceGit.checkout().setCreateBranch(true).setName("unrelated").call();
            RevCommit unrelatedCommit = commitFile(sourceGit, "main.tf", "unrelated");
            sourceGit.checkout().setName(requestedBranch).call();

            TerraformJob job = localGitJob(sourceRepository, requestedBranch);
            job.setCommitId(unrelatedCommit.getName());
            job.setRequireCommitOnBranch(requestedBranch);

            WorkspaceException e = Assertions.assertThrows(WorkspaceException.class,
                    () -> standardSetupWorkspaceImpl(job).prepareWorkspace(job));

            Assertions.assertTrue(e.getCause().getMessage().contains("is not on branch " + requestedBranch),
                    e.getCause().getMessage());
        } finally {
            FileUtils.deleteDirectory(sourceRepository);
        }
    }

    @Test
    public void runsTheTipOfTheRequiredBranchWithoutFetchingTheHistory() throws Exception {
        File sourceRepository = Files.createTempDirectory("terrakube-source").toFile();
        try (Git sourceGit = Git.init().setDirectory(sourceRepository).call()) {
            RevCommit tip = commitFile(sourceGit, "main.tf", "tip");
            String branch = sourceGit.getRepository().getBranch();
            TerraformJob job = localGitJob(sourceRepository, branch);
            job.setCommitId(tip.getName());
            job.setRequireCommitOnBranch(branch);

            UnshallowRecordingSetupWorkspace setup = new UnshallowRecordingSetupWorkspace();
            File workspaceDir = setup.prepareWorkspace(job);

            Assertions.assertFalse(setup.unshallowRepositoryCalled);
            Assertions.assertTrue(FileUtils.getFile(workspaceDir, ".git", "shallow").exists());
        } finally {
            FileUtils.deleteDirectory(sourceRepository);
        }
    }

    @Test
    public void runsAnEarlierCommitOfTheRequiredBranch() throws Exception {
        File sourceRepository = Files.createTempDirectory("terrakube-source").toFile();
        try (Git sourceGit = Git.init().setDirectory(sourceRepository).call()) {
            RevCommit planned = commitFile(sourceGit, "main.tf", "planned");
            String branch = sourceGit.getRepository().getBranch();
            commitFile(sourceGit, "main.tf", "advanced");
            TerraformJob job = localGitJob(sourceRepository, branch);
            job.setCommitId(planned.getName());
            job.setRequireCommitOnBranch(branch);

            UnshallowRecordingSetupWorkspace setup = new UnshallowRecordingSetupWorkspace();
            File workspaceDir = setup.prepareWorkspace(job);

            // The history up to the tip was fetched to prove the commit is on the branch.
            Assertions.assertTrue(setup.unshallowRepositoryCalled);
            try (Git workspaceGit = Git.open(workspaceDir)) {
                Assertions.assertEquals(planned.getName(), workspaceGit.getRepository().resolve("HEAD").getName());
            }
        } finally {
            FileUtils.deleteDirectory(sourceRepository);
        }
    }

    @Test
    public void runsTheTipWhenNoCommitIsNamed() throws Exception {
        File sourceRepository = Files.createTempDirectory("terrakube-source").toFile();
        try (Git sourceGit = Git.init().setDirectory(sourceRepository).call()) {
            commitFile(sourceGit, "main.tf", "tip");
            String branch = sourceGit.getRepository().getBranch();
            TerraformJob job = localGitJob(sourceRepository, branch);
            job.setRequireCommitOnBranch(branch);

            File workspaceDir = standardSetupWorkspaceImpl(job).prepareWorkspace(job);

            Assertions.assertTrue(FileUtils.getFile(workspaceDir, "main.tf").exists());
        } finally {
            FileUtils.deleteDirectory(sourceRepository);
        }
    }

    @Test
    public void checksATagWhenTheWorkspaceBranchNamesOne() throws Exception {
        File sourceRepository = Files.createTempDirectory("terrakube-source").toFile();
        try (Git sourceGit = Git.init().setDirectory(sourceRepository).call()) {
            RevCommit tagged = commitFile(sourceGit, "main.tf", "tagged");
            sourceGit.tag().setName("v1.0.0").call();
            TerraformJob job = localGitJob(sourceRepository, "v1.0.0");
            job.setRequireCommitOnBranch("v1.0.0");

            File workspaceDir = standardSetupWorkspaceImpl(job).prepareWorkspace(job);

            try (Git workspaceGit = Git.open(workspaceDir)) {
                Assertions.assertEquals(tagged.getName(), workspaceGit.getRepository().resolve("HEAD").getName());
            }
        } finally {
            FileUtils.deleteDirectory(sourceRepository);
        }
    }

    @Test
    public void runsAReleaseTagThatIsOnTheRequiredBranch() throws Exception {
        File sourceRepository = Files.createTempDirectory("terrakube-source").toFile();
        try (Git sourceGit = Git.init().setDirectory(sourceRepository).call()) {
            RevCommit released = commitFile(sourceGit, "main.tf", "released");
            String main = sourceGit.getRepository().getBranch();
            sourceGit.tag().setName("v1.0.0").call();
            commitFile(sourceGit, "main.tf", "advanced");
            TerraformJob job = localGitJob(sourceRepository, "v1.0.0");
            job.setRequireCommitOnBranch(main);

            File workspaceDir = standardSetupWorkspaceImpl(job).prepareWorkspace(job);

            try (Git workspaceGit = Git.open(workspaceDir)) {
                Assertions.assertEquals(released.getName(), workspaceGit.getRepository().resolve("HEAD").getName());
            }
        } finally {
            FileUtils.deleteDirectory(sourceRepository);
        }
    }

    @Test
    public void refusesAReleaseTagThatIsNotOnTheRequiredBranch() throws Exception {
        File sourceRepository = Files.createTempDirectory("terrakube-source").toFile();
        try (Git sourceGit = Git.init().setDirectory(sourceRepository).call()) {
            commitFile(sourceGit, "main.tf", "reviewed");
            String main = sourceGit.getRepository().getBranch();
            sourceGit.checkout().setCreateBranch(true).setName("feature").call();
            commitFile(sourceGit, "main.tf", "unreviewed");
            sourceGit.tag().setName("v2.0.0-rc1").call();
            sourceGit.checkout().setName(main).call();
            TerraformJob job = localGitJob(sourceRepository, "v2.0.0-rc1");
            job.setRequireCommitOnBranch(main);

            WorkspaceException e = Assertions.assertThrows(WorkspaceException.class,
                    () -> standardSetupWorkspaceImpl(job).prepareWorkspace(job));

            Assertions.assertTrue(e.getCause().getMessage().contains("is not on branch " + main),
                    e.getCause().getMessage());
        } finally {
            FileUtils.deleteDirectory(sourceRepository);
        }
    }

    @Test
    public void refusesWhenTheRequiredBranchWasNotFetched() throws Exception {
        TerraformJob job = successfulGitJob();
        job.setRequireCommitOnBranch("another-branch");

        WorkspaceException e = Assertions.assertThrows(WorkspaceException.class,
                () -> standardSetupWorkspaceImpl(job).prepareWorkspace(job));

        Assertions.assertTrue(e.getCause().getMessage().contains("was not fetched"), e.getCause().getMessage());
    }

    @Test
    public void ignoresABlankRequiredBranch() throws Exception {
        File sourceRepository = Files.createTempDirectory("terrakube-source").toFile();
        try (Git sourceGit = Git.init().setDirectory(sourceRepository).call()) {
            commitFile(sourceGit, "main.tf", "requested");
            String requestedBranch = sourceGit.getRepository().getBranch();
            sourceGit.checkout().setCreateBranch(true).setName("unrelated").call();
            RevCommit unrelatedCommit = commitFile(sourceGit, "main.tf", "unrelated");
            sourceGit.checkout().setName(requestedBranch).call();
            TerraformJob job = localGitJob(sourceRepository, requestedBranch);
            job.setCommitId(unrelatedCommit.getName());
            job.setRequireCommitOnBranch(" ");

            File workspaceDir = standardSetupWorkspaceImpl(job).prepareWorkspace(job);

            try (Git workspaceGit = Git.open(workspaceDir)) {
                Assertions.assertEquals(unrelatedCommit.getName(), workspaceGit.getRepository().resolve("HEAD").getName());
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

    private static HttpServer startServer(int statusCode, byte[] body, String locationHeader,
            AtomicInteger requestCount, AtomicReference<String> authHeader) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (requestCount != null) {
                requestCount.incrementAndGet();
            }
            if (authHeader != null) {
                authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            }
            if (locationHeader != null) {
                exchange.getResponseHeaders().add("Location", locationHeader);
            }
            byte[] responseBody = body != null ? body : new byte[0];
            exchange.sendResponseHeaders(statusCode, responseBody.length);
            try (var os = exchange.getResponseBody()) {
                os.write(responseBody);
            }
        });
        server.start();
        return server;
    }

    private TerraformJob httpTarGzJob(HttpServer server) {
        TerraformJob job = new TerraformJob();
        job.setOrganizationId("ze-org");
        job.setWorkspaceId("ze-ws");
        job.setJobId("9040");
        job.setStepId("step-1");
        job.setSource("http://127.0.0.1:" + server.getAddress().getPort()
                + "/remote/tfe/v2/configuration-versions/" + UUID.randomUUID() + "/terraformContent.tar.gz");
        job.setBranch("remote-content");
        job.setEnvironmentVariables(new HashMap<String, String>());
        return job;
    }

    private SetupWorkspaceImpl httpSetupWorkspaceImpl(HttpServer server, TerraformJob job, WorkspaceSecurity security) {
        String apiUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        String overrideSource = job != null && "remote-content".equals(job.getBranch()) ? job.getSource() : null;
        return new SetupWorkspaceImpl(security, false, new NoopTerraformExecutor(),
                apiUrl, terrakubeClient(overrideSource), new TarGzArchiver());
    }

    @Test
    public void downloadsAndUnpacksTarGz() throws Exception {
        byte[] tarBytes = Files.readAllBytes(Path.of(terraformTarGz()));
        HttpServer server = startServer(200, tarBytes, null, null, null);
        try {
            TerraformJob job = httpTarGzJob(server);
            SetupWorkspace setup = httpSetupWorkspaceImpl(server, job, new NoopWorkspaceSecurity());
            File workspaceDir = setup.prepareWorkspace(job);
            File terrformDir = FileUtils.getFile(workspaceDir, "main.tf");
            Assertions.assertTrue(terrformDir.exists());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void keepsExecutableBitFromTarGz() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        byte[] tarBytes = Files.readAllBytes(Path.of(executableScriptTarGz()));
        HttpServer server = startServer(200, tarBytes, null, null, null);
        try {
            TerraformJob job = httpTarGzJob(server);
            SetupWorkspace setup = httpSetupWorkspaceImpl(server, job, new NoopWorkspaceSecurity());
            File workspaceDir = setup.prepareWorkspace(job);
            Assertions.assertTrue(Files.isExecutable(FileUtils.getFile(workspaceDir, "scripts", "run.sh").toPath()));
            Assertions.assertFalse(Files.isExecutable(FileUtils.getFile(workspaceDir, "main.tf").toPath()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void reportsFailureonBadTarGz() throws Exception {
        HttpServer server = startServer(404, new byte[0], null, null, null);
        try {
            TerraformJob job = httpTarGzJob(server);
            SetupWorkspace setup = httpSetupWorkspaceImpl(server, job, new NoopWorkspaceSecurity());
            WorkspaceException e = Assertions.assertThrows(WorkspaceException.class, () -> setup.prepareWorkspace(job));
            Assertions.assertEquals(FileNotFoundException.class, e.getCause().getClass());
        } finally {
            server.stop(0);
        }
    }

    // Defense in depth for #3671: a storage read failure upstream should surface as a 503 from
    // the api module, but if one still slips through as empty bytes, this must fail clearly
    // instead of letting gzip parsing produce the confusing "Input is not in the .gz format".
    @Test
    public void reportsFailureWhenDownloadedTarGzIsEmpty() throws Exception {
        HttpServer server = startServer(200, new byte[0], null, null, null);
        try {
            TerraformJob job = httpTarGzJob(server);
            job.setJobId("9042");
            SetupWorkspace setup = httpSetupWorkspaceImpl(server, job, new NoopWorkspaceSecurity());

            WorkspaceException e = Assertions.assertThrows(WorkspaceException.class, () -> setup.prepareWorkspace(job));

            Assertions.assertEquals(IOException.class, e.getCause().getClass());
            Assertions.assertTrue(e.getCause().getMessage().contains("empty"));
            Assertions.assertTrue(e.getCause().getMessage().contains("9042"));
        } finally {
            server.stop(0);
        }
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
                "https://terrakube-api.example.com", terrakubeClient(null), new TarGzArchiver());

        WorkspaceException e = Assertions.assertThrows(WorkspaceException.class, () -> setup.prepareWorkspace(job));

        Assertions.assertEquals(IOException.class, e.getCause().getClass());
        Assertions.assertTrue(e.getCause().getMessage().contains("9041"));
    }

    @Test
    public void refusesUntrustedExternalRemoteContentUrlWithoutSendingToken() throws Exception {
        AtomicInteger attackerRequests = new AtomicInteger(0);
        HttpServer attackerServer = startServer(200, new byte[] {1, 2, 3}, null, attackerRequests, null);
        AtomicInteger bareTokenCalls = new AtomicInteger(0);
        AtomicInteger scopedTokenCalls = new AtomicInteger(0);
        WorkspaceSecurity recordingSecurity = new NoopWorkspaceSecurity() {
            @Override
            public String generateAccessToken(int minutes) {
                bareTokenCalls.incrementAndGet();
                return "bare-executor-token";
            }

            @Override
            public String generateAccessToken(int minutes, String organizationId, String workspaceId, String jobId, String stepId) {
                scopedTokenCalls.incrementAndGet();
                return "scoped-token";
            }
        };
        try {
            String maliciousUrl = "http://127.0.0.1:" + attackerServer.getAddress().getPort()
                    + "/remote/tfe/v2/configuration-versions/" + UUID.randomUUID() + "/terraformContent.tar.gz";
            TerraformJob job = new TerraformJob();
            job.setOrganizationId("ze-org");
            job.setWorkspaceId("ze-ws");
            job.setJobId("9043");
            job.setStepId("step-1");
            job.setSource(maliciousUrl);
            job.setBranch("remote-content");
            job.setEnvironmentVariables(new HashMap<>());

            SetupWorkspaceImpl setup = new SetupWorkspaceImpl(recordingSecurity, false, new NoopTerraformExecutor(),
                    "https://terrakube-api.example.com", terrakubeClient(maliciousUrl), new TarGzArchiver());

            WorkspaceException e = Assertions.assertThrows(WorkspaceException.class, () -> setup.prepareWorkspace(job));

            Assertions.assertInstanceOf(IOException.class, e.getCause());
            Assertions.assertTrue(e.getCause().getMessage().contains("untrusted URL"));
            Assertions.assertEquals(0, attackerRequests.get());
            Assertions.assertEquals(0, bareTokenCalls.get());
            Assertions.assertEquals(0, scopedTokenCalls.get());
        } finally {
            attackerServer.stop(0);
        }
    }

    @Test
    public void refusesUserInfoSpoofedAndOffPathRemoteContentUrls() {
        String apiUrl = "https://terrakube-api.localhost";
        String id = UUID.randomUUID().toString();

        Assertions.assertThrows(IOException.class, () -> SetupWorkspaceImpl.validateConfigurationVersionSource(
                "https://terrakube-api.localhost@attacker.example.com/remote/tfe/v2/configuration-versions/" + id + "/terraformContent.tar.gz",
                apiUrl));
        Assertions.assertThrows(IOException.class, () -> SetupWorkspaceImpl.validateConfigurationVersionSource(
                "https://attacker.example.com/x.tar.gz",
                apiUrl));
        Assertions.assertThrows(IOException.class, () -> SetupWorkspaceImpl.validateConfigurationVersionSource(
                "https://terrakube-api.localhost/remote/tfe/v2/configuration-versions/" + id + "/../other/terraformContent.tar.gz",
                apiUrl));
        Assertions.assertThrows(IOException.class, () -> SetupWorkspaceImpl.validateConfigurationVersionSource(
                "https://terrakube-api.localhost/api/v1/organization/123",
                apiUrl));
        Assertions.assertThrows(IOException.class, () -> SetupWorkspaceImpl.validateConfigurationVersionSource(
                "http://terrakube-api.localhost/remote/tfe/v2/configuration-versions/" + id + "/terraformContent.tar.gz",
                apiUrl));
        Assertions.assertThrows(IOException.class, () -> SetupWorkspaceImpl.validateConfigurationVersionSource(
                "https://terrakube-api.localhost:8443/remote/tfe/v2/configuration-versions/" + id + "/terraformContent.tar.gz",
                apiUrl));
        Assertions.assertThrows(IOException.class, () -> SetupWorkspaceImpl.validateConfigurationVersionSource(
                "file:///etc/passwd",
                apiUrl));
    }

    @Test
    public void refusesHttpRedirectWithoutForwardingAuthorizationHeader() throws Exception {
        AtomicInteger attackerRequests = new AtomicInteger(0);
        HttpServer attackerServer = startServer(200, new byte[] {1, 2, 3}, null, attackerRequests, null);
        String redirectTarget = "http://127.0.0.1:" + attackerServer.getAddress().getPort() + "/steal";
        HttpServer apiServer = startServer(302, new byte[0], redirectTarget, null, null);
        try {
            TerraformJob job = httpTarGzJob(apiServer);
            SetupWorkspaceImpl setup = httpSetupWorkspaceImpl(apiServer, job, new NoopWorkspaceSecurity());

            WorkspaceException e = Assertions.assertThrows(WorkspaceException.class, () -> setup.prepareWorkspace(job));

            Assertions.assertInstanceOf(IOException.class, e.getCause());
            Assertions.assertTrue(e.getCause().getMessage().contains("redirect"));
            Assertions.assertEquals(0, attackerRequests.get());
        } finally {
            apiServer.stop(0);
            attackerServer.stop(0);
        }
    }

    @Test
    public void sendsScopedWorkspaceExecutorTokenForValidRemoteContentDownload() throws Exception {
        byte[] tarBytes = Files.readAllBytes(Path.of(terraformTarGz()));
        AtomicReference<String> receivedAuthHeader = new AtomicReference<>();
        HttpServer apiServer = startServer(200, tarBytes, null, null, receivedAuthHeader);
        AtomicReference<String> capturedArgs = new AtomicReference<>();
        WorkspaceSecurity recordingSecurity = new NoopWorkspaceSecurity() {
            @Override
            public String generateAccessToken(int minutes) {
                throw new AssertionError("Bare EXECUTOR_SERVICE token must not be minted for remote-content download");
            }

            @Override
            public String generateAccessToken(int minutes, String organizationId, String workspaceId, String jobId, String stepId) {
                capturedArgs.set(minutes + ":" + organizationId + ":" + workspaceId + ":" + jobId + ":" + stepId);
                return "scoped-workspace-executor-token";
            }
        };
        try {
            TerraformJob job = httpTarGzJob(apiServer);
            job.setOrganizationId("org-123");
            job.setWorkspaceId("ws-456");
            job.setJobId("789");
            job.setStepId("step-abc");

            SetupWorkspaceImpl setup = httpSetupWorkspaceImpl(apiServer, job, recordingSecurity);
            File workspaceDir = setup.prepareWorkspace(job);

            Assertions.assertTrue(FileUtils.getFile(workspaceDir, "main.tf").exists());
            Assertions.assertEquals("1:org-123:ws-456:789:step-abc", capturedArgs.get());
            Assertions.assertEquals("Bearer scoped-workspace-executor-token", receivedAuthHeader.get());
        } finally {
            apiServer.stop(0);
        }
    }

    @Test
    public void downloadsRemoteContentFromPublicHostnameWhenApiUrlIsInternal() throws Exception {
        byte[] tarBytes = Files.readAllBytes(Path.of(terraformTarGz()));
        AtomicReference<String> receivedAuthHeader = new AtomicReference<>();

        KeyPair keyPair = generateRsaKeyPair();
        long now = System.currentTimeMillis();
        X500Name dn = new X500Name("CN=127.0.0.1");
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(dn, BigInteger.valueOf(now),
                        new Date(now - 60_000), new Date(now + 3_600_000), dn, keyPair.getPublic()).build(signer));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("server", keyPair.getPrivate(), new char[0], new Certificate[] { cert });
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        HttpsServer publicIngressServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        publicIngressServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        publicIngressServer.createContext("/", exchange -> {
            receivedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, tarBytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(tarBytes);
            }
        });
        publicIngressServer.start();

        SSLSocketFactory originalFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        HostnameVerifier originalVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> "127.0.0.1".equals(hostname));

        AtomicReference<String> capturedArgs = new AtomicReference<>();
        WorkspaceSecurity recordingSecurity = new NoopWorkspaceSecurity() {
            @Override
            public String generateAccessToken(int minutes, String organizationId, String workspaceId, String jobId, String stepId) {
                capturedArgs.set(minutes + ":" + organizationId + ":" + workspaceId + ":" + jobId + ":" + stepId);
                return "scoped-public-token";
            }
        };
        try {
            String publicUrl = "https://127.0.0.1:" + publicIngressServer.getAddress().getPort()
                    + "/remote/tfe/v2/configuration-versions/" + UUID.randomUUID() + "/terraformContent.tar.gz";
            TerraformJob job = new TerraformJob();
            job.setOrganizationId("org-split");
            job.setWorkspaceId("ws-split");
            job.setJobId("9050");
            job.setStepId("step-split");
            job.setBranch("remote-content");
            job.setRemoteContentHostname("127.0.0.1:" + publicIngressServer.getAddress().getPort());
            job.setEnvironmentVariables(new HashMap<>());

            // apiUrl points to an internal cluster service URL that differs in scheme, host, and port
            SetupWorkspaceImpl setup = new SetupWorkspaceImpl(recordingSecurity, false, new NoopTerraformExecutor(),
                    "http://terrakube-api-service:8080", terrakubeClient(publicUrl), new TarGzArchiver());

            File workspaceDir = setup.prepareWorkspace(job);

            Assertions.assertTrue(FileUtils.getFile(workspaceDir, "main.tf").exists());
            Assertions.assertEquals("1:org-split:ws-split:9050:step-split", capturedArgs.get());
            Assertions.assertEquals("Bearer scoped-public-token", receivedAuthHeader.get());
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(originalFactory);
            HttpsURLConnection.setDefaultHostnameVerifier(originalVerifier);
            publicIngressServer.stop(0);
        }
    }

    @Test
    public void refusesUntrustedHostEvenWhenInternalApiUrlAndPublicHostnameDiffer() throws Exception {
        AtomicInteger attackerRequests = new AtomicInteger(0);
        HttpServer attackerServer = startServer(200, new byte[] {1, 2, 3}, null, attackerRequests, null);
        AtomicInteger scopedTokenCalls = new AtomicInteger(0);
        WorkspaceSecurity recordingSecurity = new NoopWorkspaceSecurity() {
            @Override
            public String generateAccessToken(int minutes, String organizationId, String workspaceId, String jobId, String stepId) {
                scopedTokenCalls.incrementAndGet();
                return "scoped-token";
            }
        };
        try {
            String maliciousUrl = "http://127.0.0.1:" + attackerServer.getAddress().getPort()
                    + "/remote/tfe/v2/configuration-versions/" + UUID.randomUUID() + "/terraformContent.tar.gz";
            TerraformJob job = new TerraformJob();
            job.setOrganizationId("org-split");
            job.setWorkspaceId("ws-split");
            job.setJobId("9051");
            job.setStepId("step-split");
            job.setBranch("remote-content");
            job.setRemoteContentHostname("terrakube-api.example.com");
            job.setEnvironmentVariables(new HashMap<>());

            SetupWorkspaceImpl setup = new SetupWorkspaceImpl(recordingSecurity, false, new NoopTerraformExecutor(),
                    "http://terrakube-api-service:8080", terrakubeClient(maliciousUrl), new TarGzArchiver());

            WorkspaceException e = Assertions.assertThrows(WorkspaceException.class, () -> setup.prepareWorkspace(job));

            Assertions.assertInstanceOf(IOException.class, e.getCause());
            Assertions.assertEquals(0, attackerRequests.get());
            Assertions.assertEquals(0, scopedTokenCalls.get());
        } finally {
            attackerServer.stop(0);
        }
    }

    @Test
    public void requiresHttpsForExpectedHostnameAndRejectsHttpDowngrade() throws Exception {
        String id = UUID.randomUUID().toString();
        String internalApiUrl = "http://terrakube-api-service:8080";
        String publicHost = "terrakube-api.example.com";

        SetupWorkspaceImpl.validateConfigurationVersionSource(
                "https://terrakube-api.example.com/remote/tfe/v2/configuration-versions/" + id + "/terraformContent.tar.gz",
                internalApiUrl, publicHost);
        SetupWorkspaceImpl.validateConfigurationVersionSource(
                "http://terrakube-api-service:8080/remote/tfe/v2/configuration-versions/" + id + "/terraformContent.tar.gz",
                internalApiUrl, publicHost);

        Assertions.assertThrows(IOException.class, () -> SetupWorkspaceImpl.validateConfigurationVersionSource(
                "http://terrakube-api.example.com/remote/tfe/v2/configuration-versions/" + id + "/terraformContent.tar.gz",
                internalApiUrl, publicHost));
        Assertions.assertThrows(IOException.class, () -> SetupWorkspaceImpl.validateConfigurationVersionSource(
                "https://terrakube-api.example.com:8443/remote/tfe/v2/configuration-versions/" + id + "/terraformContent.tar.gz",
                internalApiUrl, publicHost));
        Assertions.assertThrows(IOException.class, () -> SetupWorkspaceImpl.validateConfigurationVersionSource(
                "https://terrakube-api.example.com/remote/tfe/v2/configuration-versions/" + id + "/terraformContent.tar.gz?foo=bar",
                internalApiUrl, publicHost));
    }

    @Test
    public void injectsAwsCredentialsWhenAsked() throws Exception {
        byte[] tarBytes = Files.readAllBytes(Path.of(terraformTarGz()));
        HttpServer server = startServer(200, tarBytes, null, null, null);
        try {
            TerraformJob job = httpTarGzJob(server);
            SetupWorkspace setup = httpSetupWorkspaceImpl(server, job, new NoopWorkspaceSecurity());
            job.getEnvironmentVariables().put("ENABLE_DYNAMIC_CREDENTIALS_AWS", "true");
            job.getEnvironmentVariables().put("TERRAKUBE_AWS_CREDENTIALS_FILE", "ze-secret");
            File workspaceDir = setup.prepareWorkspace(job);
            File credsFile = FileUtils.getFile(workspaceDir, "terrakube_config_dynamic_credentials_aws.txt");
            Assertions.assertTrue(credsFile.exists());
            Assertions.assertEquals("ze-secret", FileUtils.readFileToString(credsFile, Charset.defaultCharset()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void injectsGcpCredentialsWhenAsked() throws Exception {
        byte[] tarBytes = Files.readAllBytes(Path.of(terraformTarGz()));
        HttpServer server = startServer(200, tarBytes, null, null, null);
        try {
            TerraformJob job = httpTarGzJob(server);
            SetupWorkspace setup = httpSetupWorkspaceImpl(server, job, new NoopWorkspaceSecurity());
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
        } finally {
            server.stop(0);
        }
    }
}
