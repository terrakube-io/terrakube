package io.terrakube.api.plugin.vcs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.streaming.StreamingService;
import io.terrakube.api.plugin.vcs.provider.bitbucket.BitBucketWebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
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
    StorageTypeService storageTypeService;
    StreamingService streamingService;

    PrCommentService subject;

    @BeforeEach
    public void setup() {
        gitHubWebhookService = mock(GitHubWebhookService.class);
        gitLabWebhookService = mock(GitLabWebhookService.class);
        bitBucketWebhookService = mock(BitBucketWebhookService.class);
        jobRepository = mock(JobRepository.class);
        storageTypeService = mock(StorageTypeService.class);
        streamingService = mock(StreamingService.class);

        subject = new PrCommentService(
                gitHubWebhookService,
                gitLabWebhookService,
                bitBucketWebhookService,
                jobRepository,
                storageTypeService,
                streamingService);
    }

    private Job createJob(VcsType vcsType, Integer prNumber, JobStatus status) {
        Vcs vcs = new Vcs();
        vcs.setVcsType(vcsType);

        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("test-org");

        Workspace workspace = new Workspace();
        workspace.setName("test-workspace");
        workspace.setVcs(vcs);
        workspace.setOrganization(org);

        Job job = new Job();
        job.setId(42);
        job.setPrNumber(prNumber);
        job.setPrApplyEnabled(true);
        job.setStatus(status);
        job.setWorkspace(workspace);
        job.setOrganization(org);

        Step step = new Step();
        step.setId(UUID.randomUUID());
        step.setStepNumber(1);
        job.setStep(List.of(step));

        return job;
    }

    /** Stubs the last-step output fetch path (empty live logs, then stored bytes) for a job built via createJob(). */
    private void stubStepOutput(Job job, String text) {
        doReturn("").when(streamingService).getCurrentLogs(any());
        byte[] bytes = text == null ? null : text.getBytes(StandardCharsets.UTF_8);
        doReturn(bytes).when(storageTypeService).getStepOutput(any(), any(), any());
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
        stubStepOutput(job, "Plan: 3 to add, 0 to change, 1 to destroy.");

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
        stubStepOutput(job, "No changes.");

        doReturn("note-99").when(gitLabWebhookService).postMergeRequestNote(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        verify(gitLabWebhookService, times(1)).postMergeRequestNote(eq(job), any());
        verify(gitHubWebhookService, never()).postPrComment(any(), any());
    }

    @Test
    public void postPlanResultDispatchesToBitbucket() {
        Job job = createJob(VcsType.BITBUCKET, 7, JobStatus.completed);
        stubStepOutput(job, "Some plan output");

        doReturn("bb-123").when(bitBucketWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        verify(bitBucketWebhookService, times(1)).postPrComment(eq(job), any());
        verify(gitHubWebhookService, never()).postPrComment(any(), any());
    }

    @Test
    public void postPlanResultWithNoPlanOutputAndCompletedStatus() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        stubStepOutput(job, null);

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
        stubStepOutput(job, null);

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
        stubStepOutput(job, "Apply complete! Resources: 3 added, 0 changed, 1 destroyed.");

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
        stubStepOutput(job, longPlan.toString());

        doReturn("12345").when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());

        String markdown = markdownCaptor.getValue();
        assertTrue(markdown.contains("output truncated"));
    }

    @Test
    public void postPlanResultRecordsErrorWhenCommentIdIsNull() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        stubStepOutput(job, "Some plan");

        doReturn(null).when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        verify(jobRepository, times(1)).save(job);
        assertNull(job.getPrCommentId());
        assertNotNull(job.getPrCommentError());
        assertTrue(job.getPrCommentError().contains("#5"));
    }

    @Test
    public void postPlanResultRecordsErrorWhenGitHubServiceThrows() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        job.setTerraformPlan("Some plan");

        org.mockito.Mockito.doThrow(new RuntimeException("403 Forbidden"))
                .when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        verify(jobRepository, times(1)).save(job);
        assertNull(job.getPrCommentId());
        assertNotNull(job.getPrCommentError());
    }

    @Test
    public void postPlanResultClearsPreviousErrorOnSuccess() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        job.setTerraformPlan("Some plan");
        job.setPrCommentError("Failed to post comment on pull request #5. Verify permissions.");

        doReturn("12345").when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        assertEquals("12345", job.getPrCommentId());
        assertNull(job.getPrCommentError());
    }

    @Test
    public void postApplyResultRecordsErrorWhenCommentIdIsNull() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        job.setOutput("Apply complete!");

        doReturn(null).when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postApplyResult(job);

        verify(jobRepository, times(1)).save(job);
        assertNotNull(job.getPrCommentError());
    }

    @Test
    public void postPlanResultDoesNotRecordErrorForUnsupportedVcs() {
        Job job = createJob(VcsType.AZURE_DEVOPS, 5, JobStatus.completed);
        job.setTerraformPlan("Some plan");

        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        assertNull(job.getPrCommentError());
        verify(gitHubWebhookService, never()).postPrComment(any(), any());
    }

    @Test
    public void postPlanResultUsesDiffFenceForPlanBody() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        stubStepOutput(job, "+ resource \"aws_instance\" \"example\" {\n+   ami = \"ami-123\"\n+ }");

        doReturn("12345").when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());

        assertTrue(markdownCaptor.getValue().contains("```diff"));
    }

    @Test
    public void postPlanResultExtractsChangeSummaryLine() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        stubStepOutput(job, "Some preamble\n\nPlan: 2 to add, 1 to change, 0 to destroy.\n");

        doReturn("12345").when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());

        assertTrue(markdownCaptor.getValue().contains("✅ Plan: 2 to add, 1 to change, 0 to destroy."));
    }

    @Test
    public void postPlanResultExtractsNoChangesSummaryLine() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        stubStepOutput(job, "No changes. Your infrastructure matches the configuration.\n");

        doReturn("12345").when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());

        assertTrue(markdownCaptor.getValue().contains("✅ No changes. Your infrastructure matches the configuration."));
    }

    @Test
    public void postPlanResultOmitsSummaryLineWhenPatternNotFound() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        stubStepOutput(job, "Some unusual output with no recognizable summary");

        doReturn("12345").when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());

        assertTrue(markdownCaptor.getValue().contains("<details><summary>Show Plan</summary>"));
        assertFalse(markdownCaptor.getValue().contains("✅ Plan:"));
    }

    @Test
    public void postPlanResultUsesFailedIconWhenPlanFailed() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.failed);
        stubStepOutput(job, null);

        doReturn("12345").when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());

        assertTrue(markdownCaptor.getValue().contains("❌ Plan failed"));
    }

    @Test
    public void postApplyResultUsesCompleteIconWhenCompleted() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        stubStepOutput(job, "Apply complete! Resources: 1 added, 0 changed, 0 destroyed.");

        subject.postApplyResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());

        assertTrue(markdownCaptor.getValue().contains("✅ Apply complete"));
        assertTrue(markdownCaptor.getValue().contains("<details><summary>Show Apply Output</summary>"));
    }

    @Test
    public void postApplyResultUsesFailedIconWhenFailed() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.failed);
        stubStepOutput(job, "Error: something went wrong");

        subject.postApplyResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());

        assertTrue(markdownCaptor.getValue().contains("❌ Apply failed"));
    }

    @Test
    public void postPlanResultStripsAnsiCodesFromStepOutput() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        stubStepOutput(job, "[32m+ resource \"aws_instance\" \"example\"[0m");

        doReturn("12345").when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());

        String markdown = markdownCaptor.getValue();
        assertTrue(markdown.contains("+ resource \"aws_instance\" \"example\""));
        assertFalse(markdown.contains(""));
    }

    @Test
    public void postPlanResultPrefersLiveStreamingLogsOverStoredOutput() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        doReturn("Plan: 1 to add, 0 to change, 0 to destroy.").when(streamingService).getCurrentLogs(any());

        doReturn("12345").when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());
        verify(storageTypeService, never()).getStepOutput(any(), any(), any());

        String markdown = markdownCaptor.getValue();
        assertTrue(markdown.contains("Plan: 1 to add, 0 to change, 0 to destroy."));
    }

    @Test
    public void postPlanResultHandlesJobWithNoSteps() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        job.setStep(List.of());

        doReturn("12345").when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());

        assertTrue(markdownCaptor.getValue().contains("No changes detected"));
    }

    @Test
    public void postApplyDisabledNoticeSkipsWhenPrNumberIsNull() {
        Workspace workspace = createJob(VcsType.GITHUB, 5, JobStatus.completed).getWorkspace();

        subject.postApplyDisabledNotice(workspace, null);

        verify(gitHubWebhookService, never()).postPrComment(any(), any());
    }

    @Test
    public void postApplyDisabledNoticeSkipsWhenPrNumberIsZero() {
        Workspace workspace = createJob(VcsType.GITHUB, 5, JobStatus.completed).getWorkspace();

        subject.postApplyDisabledNotice(workspace, 0);

        verify(gitHubWebhookService, never()).postPrComment(any(), any());
    }

    @Test
    public void postApplyDisabledNoticePostsNoticeWithWorkspaceAndPrNumber() {
        Workspace workspace = createJob(VcsType.GITHUB, 5, JobStatus.completed).getWorkspace();

        doReturn("999").when(gitHubWebhookService).postPrComment(any(), any());

        subject.postApplyDisabledNotice(workspace, 7);

        ArgumentCaptor<Job> jobCaptor = ArgumentCaptor.forClass(Job.class);
        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(jobCaptor.capture(), markdownCaptor.capture());

        assertEquals(workspace, jobCaptor.getValue().getWorkspace());
        assertEquals(Integer.valueOf(7), jobCaptor.getValue().getPrNumber());
        assertTrue(markdownCaptor.getValue().contains("Allow Apply via PR Comment"));
        assertTrue(markdownCaptor.getValue().contains("not enabled"));
    }

    @Test
    public void postPlanResultOmitsApplyFooterWhenApplyDisabled() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        job.setPrApplyEnabled(false);
        stubStepOutput(job, "Plan: 1 to add, 0 to change, 0 to destroy.");

        doReturn("12345").when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());

        String markdown = markdownCaptor.getValue();
        assertFalse(markdown.contains("comment: `terrakube apply`"));
        assertTrue(markdown.contains("Apply via PR comment is disabled"));
        assertTrue(markdown.contains("Allow Apply via PR Comment"));
        assertTrue(markdown.contains("terrakube plan"));
    }

    @Test
    public void postPlanResultIncludesApplyFooterWhenApplyEnabled() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        job.setPrApplyEnabled(true);
        stubStepOutput(job, "Plan: 1 to add, 0 to change, 0 to destroy.");

        doReturn("12345").when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());

        assertTrue(markdownCaptor.getValue().contains("comment: `terrakube apply`"));
    }

    @Test
    public void postPlanResultUpdatesExistingThreadCommentWhenPriorPlanJobExists() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        job.setId(99);
        stubStepOutput(job, "Plan: 1 to add, 0 to change, 0 to destroy.");

        Job priorJob = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        priorJob.setId(50);
        priorJob.setPrCommentId("existing-comment-1");

        doReturn(Optional.of(priorJob)).when(jobRepository)
                .findFirstByWorkspaceAndPrNumberAndIdNotAndAutoApplyFalseAndPrCommentIdIsNotNullOrderByIdDesc(
                        job.getWorkspace(), 5, 99);
        doReturn(true).when(gitHubWebhookService).updatePrComment(eq(job), eq("existing-comment-1"), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        verify(gitHubWebhookService, times(1)).updatePrComment(eq(job), eq("existing-comment-1"), any());
        verify(gitHubWebhookService, never()).postPrComment(any(), any());
        assertEquals("existing-comment-1", job.getPrCommentId());
    }

    @Test
    public void postPlanResultFallsBackToNewCommentWhenUpdateFails() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        job.setId(99);
        stubStepOutput(job, "Plan: 1 to add, 0 to change, 0 to destroy.");

        Job priorJob = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        priorJob.setId(50);
        priorJob.setPrCommentId("stale-comment-1");

        doReturn(Optional.of(priorJob)).when(jobRepository)
                .findFirstByWorkspaceAndPrNumberAndIdNotAndAutoApplyFalseAndPrCommentIdIsNotNullOrderByIdDesc(
                        job.getWorkspace(), 5, 99);
        doReturn(false).when(gitHubWebhookService).updatePrComment(eq(job), eq("stale-comment-1"), any());
        doReturn("new-comment-1").when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        verify(gitHubWebhookService, times(1)).updatePrComment(eq(job), eq("stale-comment-1"), any());
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), any());
        assertEquals("new-comment-1", job.getPrCommentId());
    }

    @Test
    public void jobHeaderLinksToTerrakubeUiWhenUiUrlConfigured() {
        subject.uiUrl = "https://terrakube.example.com";
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        job.getOrganization().setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        job.getWorkspace().setId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        stubStepOutput(job, "Plan: 1 to add, 0 to change, 0 to destroy.");

        doReturn("12345").when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());

        String expectedUrl = "https://terrakube.example.com/organizations/11111111-1111-1111-1111-111111111111"
                + "/workspaces/22222222-2222-2222-2222-222222222222/runs/42";
        assertTrue(markdownCaptor.getValue().contains("[#42](" + expectedUrl + ")"));
    }

    @Test
    public void jobHeaderUsesPlainReferenceWhenUiUrlNotConfigured() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        stubStepOutput(job, "Plan: 1 to add, 0 to change, 0 to destroy.");

        doReturn("12345").when(gitHubWebhookService).postPrComment(any(), any());
        doReturn(job).when(jobRepository).save(any());

        subject.postPlanResult(job);

        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService, times(1)).postPrComment(eq(job), markdownCaptor.capture());

        assertTrue(markdownCaptor.getValue().contains("**Job:** #42"));
    }

    @Test
    public void acknowledgeCompletionSkipsWhenCommandCommentIdMissing() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);

        subject.acknowledgeCompletion(job);

        verify(gitHubWebhookService, never()).addCommentReaction(any(), any(), any());
    }

    @Test
    public void acknowledgeCompletionSkipsWhenPrNumberMissing() {
        Job job = createJob(VcsType.GITHUB, null, JobStatus.completed);
        job.setCommandCommentId("998877");

        subject.acknowledgeCompletion(job);

        verify(gitHubWebhookService, never()).addCommentReaction(any(), any(), any());
    }

    @Test
    public void acknowledgeCompletionAddsThumbsUpOnGitHubSuccess() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.completed);
        job.setCommandCommentId("998877");

        subject.acknowledgeCompletion(job);

        verify(gitHubWebhookService, times(1)).addCommentReaction(job.getWorkspace(), "998877", "+1");
    }

    @Test
    public void acknowledgeCompletionAddsThumbsDownOnGitHubFailure() {
        Job job = createJob(VcsType.GITHUB, 5, JobStatus.failed);
        job.setCommandCommentId("998877");

        subject.acknowledgeCompletion(job);

        verify(gitHubWebhookService, times(1)).addCommentReaction(job.getWorkspace(), "998877", "-1");
    }

    @Test
    public void acknowledgeCompletionAddsCheckmarkOnGitLabSuccess() {
        Job job = createJob(VcsType.GITLAB, 5, JobStatus.completed);
        job.setCommandCommentId("note-1");

        subject.acknowledgeCompletion(job);

        verify(gitLabWebhookService, times(1)).addNoteReaction(job.getWorkspace(), 5, "note-1", "white_check_mark");
    }

    @Test
    public void acknowledgeCompletionAddsCrossOnGitLabFailure() {
        Job job = createJob(VcsType.GITLAB, 5, JobStatus.failed);
        job.setCommandCommentId("note-1");

        subject.acknowledgeCompletion(job);

        verify(gitLabWebhookService, times(1)).addNoteReaction(job.getWorkspace(), 5, "note-1", "x");
    }

    @Test
    public void acknowledgeCompletionIsNoOpForBitbucket() {
        Job job = createJob(VcsType.BITBUCKET, 5, JobStatus.completed);
        job.setCommandCommentId("55");

        subject.acknowledgeCompletion(job);

        verify(bitBucketWebhookService, never()).postPrComment(any(), any());
        verify(bitBucketWebhookService, never()).updatePrComment(any(), any(), any());
    }
}
