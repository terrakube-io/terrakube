package io.terrakube.api.plugin.vcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.vcs.provider.azdevops.AzDevOpsWebhookService;
import io.terrakube.api.plugin.vcs.provider.bitbucket.BitBucketWebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.WebhookEventRepository;
import io.terrakube.api.repository.WebhookRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.webhook.WebhookEventPathType;
import io.terrakube.api.rs.webhook.WebhookEventType;
import io.terrakube.api.rs.workspace.Workspace;

@ExtendWith(MockitoExtension.class)
public class WebhookServiceTest {

    WebhookRepository webhookRepository;
    WebhookEventRepository webhookEventRepository;
    GitHubWebhookService gitHubWebhookService;
    GitLabWebhookService gitLabWebhookService;
    BitBucketWebhookService bitBucketWebhookService;
    AzDevOpsWebhookService azDevOpsWebhookService;
    JobRepository jobRepository;
    ScheduleJobService scheduleJobService;
    ObjectMapper objectMapper;
    WorkspaceRepository workspaceRepository;
    PrCommentService prCommentService;

    WebhookService subject;

    Workspace workspace;
    Webhook webhook;
    WebhookEvent pullRequestEvent;

    @BeforeEach
    public void setup() {
        webhookRepository = mock(WebhookRepository.class);
        webhookEventRepository = mock(WebhookEventRepository.class);
        gitHubWebhookService = mock(GitHubWebhookService.class);
        gitLabWebhookService = mock(GitLabWebhookService.class);
        bitBucketWebhookService = mock(BitBucketWebhookService.class);
        azDevOpsWebhookService = mock(AzDevOpsWebhookService.class);
        jobRepository = mock(JobRepository.class);
        scheduleJobService = mock(ScheduleJobService.class);
        objectMapper = new ObjectMapper();
        workspaceRepository = mock(WorkspaceRepository.class);
        prCommentService = mock(PrCommentService.class);

        subject = new WebhookService(
                webhookRepository,
                webhookEventRepository,
                gitHubWebhookService,
                gitLabWebhookService,
                bitBucketWebhookService,
                azDevOpsWebhookService,
                jobRepository,
                scheduleJobService,
                objectMapper,
                workspaceRepository,
                prCommentService);

        workspace = new Workspace();
        workspace.setName("test-workspace");
        workspace.setDefaultTemplate("default-template-id");
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        workspace.setVcs(vcs);

        webhook = new Webhook();
        webhook.setWorkspace(workspace);

        pullRequestEvent = new WebhookEvent();
        pullRequestEvent.setEvent(WebhookEventType.PULL_REQUEST);
        pullRequestEvent.setBranch(".*");
        pullRequestEvent.setPath("*");
        pullRequestEvent.setPathType(WebhookEventPathType.PATTERN);
        pullRequestEvent.setTemplateId("pr-template-id");

        doReturn(List.of(pullRequestEvent))
                .when(webhookEventRepository)
                .findByWebhookAndEventOrderByPriorityAsc(webhook, WebhookEventType.PULL_REQUEST);
    }

    private WebhookResult createCommentResult(String command, int prNumber) {
        WebhookResult result = new WebhookResult();
        result.setPrComment(true);
        result.setCommentCommand(command);
        result.setPrNumber(prNumber);
        result.setBranch("main");
        result.setFileChanges(List.of("main.tf"));
        result.setCreatedBy("octocat");
        return result;
    }

    @Test
    public void planCommentAddsGitHubReactionWhenCommentIdPresent() throws Exception {
        pullRequestEvent.setPrWorkflowEnabled(true);
        WebhookResult result = createCommentResult("plan", 5);
        result.setCommentId("998877");

        Job savedJob = new Job();
        savedJob.setWorkspace(workspace);
        doReturn(savedJob).when(jobRepository).save(any());

        subject.handlePrCommentCommand(result, webhook, workspace);

        verify(gitHubWebhookService, times(1)).addCommentReaction(workspace, "998877", "eyes");
    }

    @Test
    public void planCommentSkipsReactionWhenCommentIdMissing() throws Exception {
        pullRequestEvent.setPrWorkflowEnabled(true);
        WebhookResult result = createCommentResult("plan", 5);

        Job savedJob = new Job();
        savedJob.setWorkspace(workspace);
        doReturn(savedJob).when(jobRepository).save(any());

        subject.handlePrCommentCommand(result, webhook, workspace);

        verify(gitHubWebhookService, never()).addCommentReaction(any(), any(), any());
    }

    @Test
    public void planCommentIgnoredWhenPrWorkflowDisabled() throws Exception {
        pullRequestEvent.setPrWorkflowEnabled(false);
        WebhookResult result = createCommentResult("plan", 5);

        subject.handlePrCommentCommand(result, webhook, workspace);

        verify(jobRepository, never()).save(any());
        verify(scheduleJobService, never()).createJobContext(any());
    }

    @Test
    public void planCommentCreatesJobWhenPrWorkflowEnabled() throws Exception {
        pullRequestEvent.setPrWorkflowEnabled(true);
        WebhookResult result = createCommentResult("plan", 5);

        Job savedJob = new Job();
        savedJob.setWorkspace(workspace);
        doReturn(savedJob).when(jobRepository).save(any());

        subject.handlePrCommentCommand(result, webhook, workspace);

        verify(jobRepository, times(2)).save(any());
        verify(scheduleJobService, times(1)).createJobContext(any());
        verify(prCommentService, never()).postApplyDisabledNotice(any(), any());
    }

    @Test
    public void planCommentThreadsPrApplyEnabledOntoJob() throws Exception {
        pullRequestEvent.setPrWorkflowEnabled(true);
        pullRequestEvent.setPrApplyEnabled(true);
        WebhookResult result = createCommentResult("plan", 5);

        Job savedJob = new Job();
        savedJob.setWorkspace(workspace);
        doReturn(savedJob).when(jobRepository).save(any());

        subject.handlePrCommentCommand(result, webhook, workspace);

        assertTrue(savedJob.isPrApplyEnabled());
    }

    @Test
    public void applyCommentRejectedWhenPrApplyDisabled() throws Exception {
        pullRequestEvent.setPrWorkflowEnabled(true);
        pullRequestEvent.setPrApplyEnabled(false);
        WebhookResult result = createCommentResult("apply", 7);

        subject.handlePrCommentCommand(result, webhook, workspace);

        verify(jobRepository, never()).save(any());
        verify(workspaceRepository, never()).save(any());
        verify(prCommentService, times(1)).postApplyDisabledNotice(workspace, 7);
    }

    @Test
    public void applyCommentRejectedWhenPrWorkflowDisabledEvenIfApplyEnabled() throws Exception {
        pullRequestEvent.setPrWorkflowEnabled(false);
        pullRequestEvent.setPrApplyEnabled(true);
        WebhookResult result = createCommentResult("apply", 7);

        subject.handlePrCommentCommand(result, webhook, workspace);

        verify(jobRepository, never()).save(any());
        verify(prCommentService, times(1)).postApplyDisabledNotice(workspace, 7);
    }

    @Test
    public void applyCommentLocksWorkspaceAndCreatesJobWhenEnabled() throws Exception {
        pullRequestEvent.setPrWorkflowEnabled(true);
        pullRequestEvent.setPrApplyEnabled(true);
        WebhookResult result = createCommentResult("apply", 7);

        Job savedJob = new Job();
        savedJob.setWorkspace(workspace);
        doReturn(savedJob).when(jobRepository).save(any());

        subject.handlePrCommentCommand(result, webhook, workspace);

        assertTrue(workspace.isLocked());
        verify(workspaceRepository, times(1)).save(workspace);
        verify(scheduleJobService, times(1)).createJobContext(any());
        verify(prCommentService, never()).postApplyDisabledNotice(any(), any());
    }

    @Test
    public void planCommentThreadsCommandCommentIdOntoJob() throws Exception {
        pullRequestEvent.setPrWorkflowEnabled(true);
        WebhookResult result = createCommentResult("plan", 5);
        result.setCommentId("998877");

        Job savedJob = new Job();
        savedJob.setWorkspace(workspace);
        doReturn(savedJob).when(jobRepository).save(any());

        subject.handlePrCommentCommand(result, webhook, workspace);

        assertEquals("998877", savedJob.getCommandCommentId());
    }

    @Test
    public void applyCommentThreadsCommandCommentIdOntoJob() throws Exception {
        pullRequestEvent.setPrWorkflowEnabled(true);
        pullRequestEvent.setPrApplyEnabled(true);
        WebhookResult result = createCommentResult("apply", 7);
        result.setCommentId("112233");

        Job savedJob = new Job();
        savedJob.setWorkspace(workspace);
        doReturn(savedJob).when(jobRepository).save(any());

        subject.handlePrCommentCommand(result, webhook, workspace);

        assertEquals("112233", savedJob.getCommandCommentId());
    }
}
