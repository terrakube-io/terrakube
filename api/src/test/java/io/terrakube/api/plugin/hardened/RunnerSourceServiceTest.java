package io.terrakube.api.plugin.hardened;

import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutionException;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorContext;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.vcs.provider.azdevops.AzDevOpsTokenService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RunnerSourceServiceTest {

    @TempDir
    Path dir;

    StorageTypeService storage = mock(StorageTypeService.class);
    JobRepository jobs = mock(JobRepository.class);
    AzDevOpsTokenService azure = mock(AzDevOpsTokenService.class);
    RunnerSourceService service = new RunnerSourceService(storage, jobs, azure, "api.example");
    Path origin;
    String firstCommit;
    String secondCommit;

    @BeforeEach
    void repository() throws Exception {
        origin = dir.resolve("origin");
        try (Git git = Git.init().setDirectory(origin.toFile()).setInitialBranch("main").call()) {
            Files.writeString(origin.resolve("main.tf"), "resource \"terraform_data\" \"a\" {}\n");
            Path script = Files.writeString(origin.resolve("run.sh"), "#!/bin/sh\necho hi\n");
            script.toFile().setExecutable(true);
            git.add().addFilepattern(".").call();
            firstCommit = git.commit().setMessage("one").setAuthor("t", "t@t").setCommitter("t", "t@t").call().getName();
            git.tag().setName("v1").call();
            Files.writeString(origin.resolve("main.tf"), "resource \"terraform_data\" \"b\" {}\n");
            git.add().addFilepattern(".").call();
            secondCommit = git.commit().setMessage("two").setAuthor("t", "t@t").setCommitter("t", "t@t").call().getName();
        }
        when(storage.getContentFile(anyString())).thenReturn(new byte[0]);
    }

    private ExecutorContext context(String branch, String commitId) {
        HashMap<String, String> env = new HashMap<>();
        env.put("TERRAKUBE_PRIVATE_EXTENSION_REPO_TOKEN", "secret");
        env.put("KEEP", "me");
        return ExecutorContext.builder()
                .organizationId("o").workspaceId("w").jobId("7").stepId("s").type("terraformPlan")
                .source(origin.toUri().toString()).branch(branch).commitId(commitId)
                .vcsType("PUBLIC").accessToken("vcs-token").moduleSshKey("ssh-key").connectionType("OAUTH")
                .environmentVariables(env)
                .build();
    }

    private Map<String, String> unpack(byte[] archive) throws Exception {
        Map<String, String> files = new HashMap<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new GzipCompressorInputStream(new ByteArrayInputStream(archive)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    files.put(entry.getName(), (entry.getMode() & 0111) != 0 ? "x:" + new String(tar.readAllBytes(), StandardCharsets.UTF_8)
                            : new String(tar.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return files;
    }

    private byte[] storedArchive() {
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(storage).createContentFile(eq("runner-job-7"), captor.capture());
        try {
            return captor.getValue().readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theApiClonesAndTheRunnerGetsNoCredentials() throws Exception {
        Job job = new Job();
        job.setId(7);
        ExecutorContext context = context("main", null);

        service.prepare(job, context);

        Map<String, String> files = unpack(storedArchive());
        assertThat(files.get("main.tf")).contains("\"b\"");
        assertThat(files.get("run.sh")).startsWith("x:");
        assertThat(files.get("commitHash.info")).isEqualTo(secondCommit);
        assertThat(job.getCommitId()).isEqualTo(secondCommit);
        assertThat(context.getAccessToken()).isNull();
        assertThat(context.getModuleSshKey()).isNull();
        assertThat(context.getConnectionType()).isNull();
        assertThat(context.getVcsType()).isEqualTo("PUBLIC");
        assertThat(context.getEnvironmentVariables()).doesNotContainKey("TERRAKUBE_PRIVATE_EXTENSION_REPO_TOKEN").containsKey("KEEP");
    }

    @Test
    void aRecordedCommitAndTagsAreHonoured() throws Exception {
        Job job = new Job();
        job.setId(7);
        job.setCommitId("pinned");
        service.prepare(job, context("main", firstCommit));
        assertThat(unpack(storedArchive()).get("main.tf")).contains("\"a\"");
        assertThat(job.getCommitId()).isEqualTo("pinned");

        reset(storage);
        when(storage.getContentFile(anyString())).thenReturn(new byte[0]);
        service.prepare(new Job(), context("v1", null));
        assertThat(unpack(storedArchive()).get("main.tf")).contains("\"a\"");
    }

    @Test
    void theArchiveIsReusedByLaterStepsOfTheJob() throws Exception {
        when(storage.getContentFile("runner-job-7")).thenReturn(new byte[]{1});
        service.prepare(new Job(), context("main", null));
        verify(storage, never()).createContentFile(anyString(), any());
        assertThat(service.content("7")).containsExactly(1);
    }

    @Test
    void cliDrivenConfigurationsAreCopied() throws Exception {
        ExecutorContext context = context("remote-content", null);
        context.setSource("https://api.example/remote/tfe/v2/configuration-versions/0b7a2d2c-5a0e-4a8f-9a59-1c7e4f1b2a3c/terraformContent.tar.gz");
        when(storage.getContentFile("0b7a2d2c-5a0e-4a8f-9a59-1c7e4f1b2a3c")).thenReturn(new byte[]{9});
        service.prepare(new Job(), context);
        assertThat(storedArchive()).containsExactly(9);

        context.setSource("https://evil.example/whatever");
        when(storage.getContentFile("runner-job-7")).thenReturn(new byte[0]);
        assertThatThrownBy(() -> service.prepare(new Job(), context)).isInstanceOf(ExecutionException.class);
        context.setSource("https://api.example/remote/tfe/v2/configuration-versions/11111111-1111-1111-1111-111111111111/terraformContent.tar.gz");
        assertThatThrownBy(() -> service.prepare(new Job(), context)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void policyEvaluationsNeedNoSource() throws Exception {
        ExecutorContext context = context("main", null);
        context.setType("policyEvaluation");
        service.prepare(new Job(), context);
        verify(storage, never()).createContentFile(anyString(), any());
        assertThat(context.getAccessToken()).isNull();
    }

    @Test
    void unreachableRepositoriesFailTheDispatch() {
        ExecutorContext context = context("main", null);
        context.setSource(dir.resolve("missing").toUri().toString());
        assertThatThrownBy(() -> service.prepare(new Job(), context)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void sshSourcesUseTheKeyOnlyInsideTheApi() {
        ExecutorContext context = context("main", null);
        context.setSource("ssh://git@127.0.0.1:1/repo.git");
        context.setVcsType("SSH~id_ed25519");
        context.setAccessToken("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----");
        assertThatThrownBy(() -> service.prepare(new Job(), context)).isInstanceOf(ExecutionException.class);
        context.setAccessToken("-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");
        assertThatThrownBy(() -> service.prepare(new Job(), context)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void referencedPlansAreCopiedIntoTheCurrentJob() {
        Job job = new Job();
        job.setId(7);
        job.setTerraformPlan("https://api.example/tfstate/v1/organization/o/workspace/w/jobId/3/step/p/terraform.tfstate");
        when(storage.getTerraformPlan("o", "w", "3", "p")).thenReturn(new byte[]{5});
        ExecutorContext context = context("main", null);
        context.setType("policyEvaluation");

        service.rebindReferencedPlan(job, context);

        verify(storage).uploadTerraformPlan(eq("o"), eq("w"), eq("7"), eq("s"), eq(new byte[]{5}));
        assertThat(job.getTerraformPlan()).isEqualTo("https://api.example/tfstate/v1/organization/o/workspace/w/jobId/7/step/s/terraform.tfstate");

        service.rebindReferencedPlan(job, context);
        verify(storage, times(1)).uploadTerraformPlan(any(), any(), any(), any(), any());

        job.setTerraformPlan("https://api.example/tfstate/v1/organization/o/workspace/other/jobId/3/step/p/terraform.tfstate");
        service.rebindReferencedPlan(job, context);
        assertThat(job.getTerraformPlan()).isNull();

        job.setTerraformPlan("https://api.example/tfstate/v1/organization/o/workspace/w/jobId/4/step/p/terraform.tfstate");
        when(storage.getTerraformPlan("o", "w", "4", "p")).thenReturn(new byte[0]);
        service.rebindReferencedPlan(job, context);
        assertThat(job.getTerraformPlan()).isNull();

        job.setTerraformPlan(" ");
        service.rebindReferencedPlan(job, context);
        verify(jobs, times(3)).save(job);
    }

    @Test
    void credentialsFollowTheVcsConventions() {
        when(azure.getAzureDefaultToken()).thenReturn("aad");
        assertThat(user(service.credentials("GITHUB", "OAUTH", "t"))).isEqualTo("t");
        assertThat(user(service.credentials("GITHUB", "APP", "t"))).isEqualTo("x-access-token");
        assertThat(user(service.credentials("BITBUCKET", null, "t"))).isEqualTo("x-token-auth");
        assertThat(user(service.credentials("GITLAB", null, "t"))).isEqualTo("oauth2");
        assertThat(user(service.credentials("AZURE_DEVOPS", null, "t"))).isEqualTo("dummy");
        assertThat(user(service.credentials("AZURE_SP_MI", null, null))).isEqualTo("dummy");
        assertThat(service.credentials("PUBLIC", null, null)).isNull();
        assertThat(service.credentials(null, null, null)).isNull();
    }

    private static String user(org.eclipse.jgit.transport.CredentialsProvider provider) {
        org.eclipse.jgit.transport.CredentialItem.Username username = new org.eclipse.jgit.transport.CredentialItem.Username();
        ((UsernamePasswordCredentialsProvider) provider).get(null, username);
        return username.getValue();
    }
}
