package io.terrakube.api.plugin.hardened;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutionException;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorContext;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.vcs.provider.azdevops.AzDevOpsTokenService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.sshd.JGitKeyCache;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.eclipse.jgit.util.FS;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "io.terrakube.hardened", name = "enabled", havingValue = "true")
public class RunnerSourceService {

    static final List<String> CREDENTIAL_VARIABLES = List.of("TERRAKUBE_PRIVATE_EXTENSION_REPO_TOKEN",
            "TERRAKUBE_PRIVATE_EXTENSION_REPO_TOKEN_TYPE", "TERRAKUBE_PRIVATE_EXTENSION_REPO_TYPE");
    private static final Pattern PLAN_URL = Pattern.compile(
            ".*/tfstate/v1/organization/([^/]+)/workspace/([^/]+)/jobId/([^/]+)/step/([^/]+)/terraform\\.tfstate$");
    private static final Pattern CONFIGURATION_URL = Pattern.compile(".*/configuration-versions/([0-9a-fA-F-]{36})/terraformContent\\.tar\\.gz$");
    private static final Pattern COMMIT_ID = Pattern.compile("^[a-fA-F0-9]{40}$");

    private final StorageTypeService storageTypeService;
    private final JobRepository jobRepository;
    private final AzDevOpsTokenService azDevOpsTokenService;
    private final String hostname;

    public RunnerSourceService(StorageTypeService storageTypeService, JobRepository jobRepository,
                               AzDevOpsTokenService azDevOpsTokenService, @Value("${io.terrakube.hostname}") String hostname) {
        this.storageTypeService = storageTypeService;
        this.jobRepository = jobRepository;
        this.azDevOpsTokenService = azDevOpsTokenService;
        this.hostname = hostname;
    }

    public static String contentId(String jobId) {
        return "runner-job-" + jobId;
    }

    public byte[] content(String jobId) {
        return storageTypeService.getContentFile(contentId(jobId));
    }

    public void prepare(Job job, ExecutorContext context) throws ExecutionException {
        rebindReferencedPlan(job, context);
        if (!"policyEvaluation".equals(context.getType()) && content(context.getJobId()).length == 0) {
            byte[] archive = "remote-content".equals(context.getBranch()) ? uploadedConfiguration(context) : clone(job, context);
            storageTypeService.createContentFile(contentId(context.getJobId()), new ByteArrayInputStream(archive));
        }
        context.setAccessToken(null);
        context.setModuleSshKey(null);
        context.setConnectionType(null);
        context.setVcsType("PUBLIC");
        CREDENTIAL_VARIABLES.forEach(context.getEnvironmentVariables()::remove);
    }

    void rebindReferencedPlan(Job job, ExecutorContext context) {
        String planUrl = job.getTerraformPlan();
        if (planUrl == null || planUrl.isBlank()) {
            return;
        }
        Matcher matcher = PLAN_URL.matcher(planUrl);
        String organizationId = context.getOrganizationId();
        String workspaceId = context.getWorkspaceId();
        if (!matcher.matches() || !matcher.group(1).equals(organizationId) || !matcher.group(2).equals(workspaceId)) {
            log.warn("Job {} references a plan outside its workspace, ignoring it", job.getId());
            job.setTerraformPlan(null);
            jobRepository.save(job);
            return;
        }
        if (matcher.group(3).equals(context.getJobId())) {
            return;
        }
        byte[] plan = storageTypeService.getTerraformPlan(organizationId, workspaceId, matcher.group(3), matcher.group(4));
        if (plan == null || plan.length == 0) {
            job.setTerraformPlan(null);
        } else {
            storageTypeService.uploadTerraformPlan(organizationId, workspaceId, context.getJobId(), context.getStepId(), plan);
            job.setTerraformPlan(String.format("https://%s/tfstate/v1/organization/%s/workspace/%s/jobId/%s/step/%s/terraform.tfstate",
                    hostname, organizationId, workspaceId, context.getJobId(), context.getStepId()));
        }
        jobRepository.save(job);
    }

    private byte[] uploadedConfiguration(ExecutorContext context) throws ExecutionException {
        Matcher matcher = context.getSource() == null ? null : CONFIGURATION_URL.matcher(context.getSource());
        if (matcher == null || !matcher.matches()) {
            throw new ExecutionException(new Throwable("Job " + context.getJobId() + " has no uploaded configuration"));
        }
        byte[] archive = storageTypeService.getContentFile(matcher.group(1));
        if (archive == null || archive.length == 0) {
            throw new ExecutionException(new Throwable("Configuration " + matcher.group(1) + " is empty"));
        }
        return archive;
    }

    byte[] clone(Job job, ExecutorContext context) throws ExecutionException {
        Path work = null;
        try {
            work = Files.createTempDirectory("terrakube-runner-source");
            File repository = work.resolve("repository").toFile();
            File sshFolder = work.resolve("ssh").toFile();
            boolean ssh = context.getVcsType() != null && context.getVcsType().startsWith("SSH");
            SshdSessionFactory sessionFactory = ssh ? sshSessionFactory(sshFolder, context.getAccessToken()) : null;
            CredentialsProvider credentials = ssh ? null : credentials(context.getVcsType(), context.getConnectionType(), context.getAccessToken());
            try {
                String branchRef = branchRef(context, sessionFactory, credentials);
                CloneCommand clone = configure(Git.cloneRepository(), sessionFactory, credentials)
                        .setURI(context.getSource())
                        .setDirectory(repository)
                        .setBranch(context.getBranch())
                        .setCloneSubmodules(true)
                        .setDepth(1)
                        .setBranchesToClone(List.of(branchRef));
                try (Git git = clone.call()) {
                    String commitId = checkout(git, context, sessionFactory, credentials);
                    FileUtils.writeStringToFile(new File(repository, "commitHash.info"), commitId, StandardCharsets.UTF_8);
                    if (job.getCommitId() == null || job.getCommitId().isBlank()) {
                        job.setCommitId(commitId);
                        jobRepository.save(job);
                    }
                    context.setCommitId(commitId);
                }
            } finally {
                if (sessionFactory != null) {
                    sessionFactory.close();
                }
            }
            return archive(repository.toPath());
        } catch (IOException | GitAPIException e) {
            throw new ExecutionException(new Throwable("Unable to fetch the workspace source for job " + context.getJobId()
                    + ": " + e.getMessage(), e));
        } finally {
            if (work != null) {
                FileUtils.deleteQuietly(work.toFile());
            }
        }
    }

    private String branchRef(ExecutorContext context, SshdSessionFactory sessionFactory, CredentialsProvider credentials) {
        String headRef = Constants.R_HEADS + context.getBranch();
        String tagRef = Constants.R_TAGS + context.getBranch();
        try {
            Map<String, ?> refs = configure(Git.lsRemoteRepository().setRemote(context.getSource()), sessionFactory, credentials)
                    .setHeads(true).setTags(true).callAsMap();
            return !refs.containsKey(headRef) && refs.containsKey(tagRef) ? tagRef : headRef;
        } catch (GitAPIException e) {
            return headRef;
        }
    }

    private String checkout(Git git, ExecutorContext context, SshdSessionFactory sessionFactory,
                            CredentialsProvider credentials) throws GitAPIException, IOException {
        String commitId = context.getCommitId();
        if (commitId != null && !commitId.isBlank()) {
            if (COMMIT_ID.matcher(commitId).matches() && !commitExists(git, commitId)) {
                try {
                    configure(git.fetch(), sessionFactory, credentials).setRemote("origin")
                            .setRefSpecs(new RefSpec(commitId)).setDepth(1).call();
                } catch (GitAPIException e) {
                    configure(git.fetch(), sessionFactory, credentials).setRemote("origin").setUnshallow(true).call();
                }
            }
            git.checkout().setName(commitId).call();
        }
        ObjectId head = git.getRepository().resolve(Constants.HEAD);
        return head == null ? "" : head.getName();
    }

    private static boolean commitExists(Git git, String commitId) throws IOException {
        ObjectId objectId = git.getRepository().resolve(commitId);
        if (objectId == null) {
            return false;
        }
        try (org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            walk.parseCommit(objectId);
            return true;
        } catch (org.eclipse.jgit.errors.MissingObjectException e) {
            return false;
        }
    }

    private <C extends TransportCommand<C, ?>> C configure(C command, SshdSessionFactory sessionFactory, CredentialsProvider credentials) {
        if (sessionFactory != null) {
            return command.setTransportConfigCallback(transport -> ((SshTransport) transport).setSshSessionFactory(sessionFactory));
        }
        if (credentials != null) {
            command.setCredentialsProvider(credentials);
        }
        return command;
    }

    CredentialsProvider credentials(String vcsType, String connectionType, String accessToken) {
        if (vcsType == null) {
            return null;
        }
        return switch (vcsType) {
            case "GITHUB" -> "OAUTH".equals(connectionType)
                    ? new UsernamePasswordCredentialsProvider(accessToken, "")
                    : new UsernamePasswordCredentialsProvider("x-access-token", accessToken);
            case "BITBUCKET" -> new UsernamePasswordCredentialsProvider("x-token-auth", accessToken);
            case "GITLAB" -> new UsernamePasswordCredentialsProvider("oauth2", accessToken);
            case "AZURE_DEVOPS" -> new UsernamePasswordCredentialsProvider("dummy", accessToken);
            case "AZURE_SP_MI" -> new UsernamePasswordCredentialsProvider("dummy", azDevOpsTokenService.getAzureDefaultToken());
            default -> null;
        };
    }

    private SshdSessionFactory sshSessionFactory(File sshFolder, String privateKey) throws IOException {
        Files.createDirectories(sshFolder.toPath());
        File key = new File(sshFolder, privateKey.startsWith("-----BEGIN RSA PRIVATE KEY-----") ? "id_rsa" : "id_ed25519");
        FileUtils.writeStringToFile(key, privateKey.endsWith("\n") ? privateKey : privateKey + "\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(key.toPath(), Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            log.debug("POSIX permissions not supported for {}", key);
        }
        return new SshdSessionFactoryBuilder()
                .setServerKeyDatabase((home, ssh) -> new ServerKeyDatabase() {
                    @Override
                    public List<PublicKey> lookup(String connectAddress, InetSocketAddress remoteAddress, Configuration config) {
                        return List.of();
                    }

                    @Override
                    public boolean accept(String connectAddress, InetSocketAddress remoteAddress, PublicKey serverKey,
                                          Configuration config, CredentialsProvider provider) {
                        return true;
                    }
                })
                .setPreferredAuthentications("publickey")
                .setHomeDirectory(FS.DETECTED.userHome())
                .setSshDirectory(sshFolder)
                .build(new JGitKeyCache());
    }

    static byte[] archive(Path root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes));
             Stream<Path> files = Files.walk(root)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Path path : (Iterable<Path>) files.sorted()::iterator) {
                if (path.equals(root) || Files.isSymbolicLink(path)) {
                    continue;
                }
                String name = root.relativize(path).toString().replace(File.separatorChar, '/');
                TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), Files.isDirectory(path) ? name + "/" : name);
                if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                    entry.setMode(entry.getMode() | 0111);
                }
                tar.putArchiveEntry(entry);
                if (Files.isRegularFile(path)) {
                    Files.copy(path, tar);
                }
                tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }
}
