package io.terrakube.api.plugin.vcs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import io.terrakube.api.plugin.vcs.provider.bitbucket.BitBucketWebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.workspace.Workspace;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
public class PrCommentServiceTest {

    GitHubWebhookService gitHubWebhookService;
    GitLabWebhookService gitLabWebhookService;
    BitBucketWebhookService bitBucketWebhookService;
    JobRepository jobRepository;

    PrCommentService subject;

    @BeforeEach
    public void setup() {
        gitHubWebhookService = mock(GitHubWebhookService.class);
        gitLabWebhookService = mock(GitLabWebhookService.class);
        bitBucketWebhookService = mock(BitBucketWebhookService.class);
        jobRepository = mock(JobRepository.class);

        subject = new PrCommentService(
                gitHubWebhookService,
                gitLabWebhookService,
                bitBucketWebhookService,
                jobRepository);
    }

    private Job createJob(VcsType vcsType, Integer prNumber, JobStatus status) {
        Vcs vcs = new Vcs();
        vcs.setVcsType(vcsType);

        Organization org = new Organization();
        org.setName("test-org");

        Workspace workspace = new Workspace();
        workspace.setName("test-workspace");
        workspace.setVcs(vcs);
        workspace.setOrganization(org);

        Job job = new Job();
        job.setId(42);
        job.setPrNumber(prNumber);
        job.setStatus(status);
        job.setWorkspace(workspace);
        job.setOrganization(org);

        return job;
    }

    @Test
    public void postPlanResultSkipsWhenPrNumberIsNull() {
        Job job = createJob(VcsType.GITHUB, null, JobStatus.completed);

        subject.postPlanResult(job);

        verify(gitHubWebhookService, never()).postPrComment(any(), any());
    }

    @Test
    public void postPlanResultSkipsWhenPrNumberIsZero() {
        Job job = createJob(VcsType.GITHUB, 0, JobStatus.completed);

        subject.postPlanResult(job);

        verify(gitHubWebhookService, never()).postPrComment(any(), any());
    }

    @Test
    public void postPlanResultDispatchesToGitHub() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        job.setTerraformPlan("Plan: 3 to add, 0 to change, 1 to destroy.");

        doReturn("12345").when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());

        String markdown = markdownCaptor.getValue();
        assertTrue(markdown.contains("## Terrakube Plan Output"));
        assertTrue(markdown.contains("test-workspace"));
        assertTrue(markdown.contains("Plan: 3 to add"));
        assertTrue(markdown.contains("terrakube apply"));
        assertTrue(markdown.contains("terrakube plan"));

        assertEquals("12345", job.getPrCommentId());
        verify(jobRepository, times(1)).save(job);
    }

    @Test
    public void postPlanResultDispatchesToGitLab() {
        Job job = createJob(VcsType.GITLAB, 10, JobStatus.completed);
        job.setTerraformPlan("No changes.");

        doReturn("note-99").when(gitLabWebhookService).postMergeRequestNote(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        verify(gitLabWebhookService, times(1)).postMergeRequestNote(eq(job), any());
        verify(gitHubWebhookService, never()).postPrComment(any(), any());
    }

    @Test
    public void postPlanResultDispatchesToBitbucket() {
        Job job = createJob(VcsType.BITBUCKET, 7, JobStatus.completed);
        job.setTerraformPlan("Some plan output");

        doReturn("bb-123").when(bitBucketWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        verify(bitBucketWebhookService, times(1)).postPrComment(eq(job), any());
        verify(gitHubWebhookService, never()).postPrComment(any(), any());
    }

    @Test
    public void postPlanResultWithNoPlanOutputAndCompletedStatus() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        job.setTerraformPlan(null);

        doReturn("12345").when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());

        String markdown = markdownCaptor.getValue();
        assertTrue(markdown.contains("No changes detected"));
    }

    @Test
    public void postPlanResultWithFailedStatus() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.failed);
        job.setTerraformPlan(null);

        doReturn("12345").when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());

        String markdown = markdownCaptor.getValue();
        assertTrue(markdown.contains("Plan failed"));
    }

    @Test
    public void postApplyResultSkipsWhenPrNumberIsNull() {
        Job job = createJob(VcsType.GITHUB, null, JobStatus.completed);

        subject.postApplyResult(job);

        verify(gitHubWebhookService, never()).postPrComment(any(), any());
    }

    @Test
    public void postApplyResultDispatchesToGitHub() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        job.setOutput("Apply complete! Resources: 3 added, 0 changed, 1 destroyed.");

        subject.postApplyResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());

        String markdown = markdownCaptor.getValue();
        assertTrue(markdown.contains("## Terrakube Apply Output"));
        assertTrue(markdown.contains("test-workspace"));
        assertTrue(markdown.contains("Apply complete!"));
    }

    @Test
    public void postPlanResultTruncatesLongOutput() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        // Create a string longer than 60000 chars
        StringBuilder longPlan = new StringBuilder();
        for (int i = 0; i < 7000; i++) {
            longPlan.append("Resource aws_instance.test will be created\n");
        }
        job.setTerraformPlan(longPlan.toString());

        doReturn("12345").when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());

        String markdown = markdownCaptor.getValue();
        assertTrue(markdown.contains("output truncated"));
    }

    @Test
    public void postPlanResultDoesNotSaveWhenCommentIdIsNull() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        job.setTerraformPlan("Some plan");

        doReturn(null).when(gitHubWebhookService).postPrComment(any(), any());

        subject.postPlanResult(job);

        verify(jobRepository, never()).save(any());
        assertNull(job.getPrCommentId());
    }
}
