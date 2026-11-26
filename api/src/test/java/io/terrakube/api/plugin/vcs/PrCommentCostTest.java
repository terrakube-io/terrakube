package io.terrakube.api.plugin.vcs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.cost.InfracostContextExtractor;
import io.terrakube.api.plugin.logs.StepOutputReader;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.vcs.provider.bitbucket.BitBucketWebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.PolicyEvaluationRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrCommentCostTest {

    private GitHubWebhookService gitHubWebhookService;
    private JobRepository jobRepository;
    private StepRepository stepRepository;
    private StorageTypeService storageTypeService;
    private PrCommentService prCommentService;

    @BeforeEach
    void setUp() {
        gitHubWebhookService = mock(GitHubWebhookService.class);
        GitLabWebhookService gitLabWebhookService = mock(GitLabWebhookService.class);
        BitBucketWebhookService bitBucketWebhookService = mock(BitBucketWebhookService.class);
        jobRepository = mock(JobRepository.class);
        stepRepository = mock(StepRepository.class);
        storageTypeService = mock(StorageTypeService.class);
        StepOutputReader stepOutputReader = mock(StepOutputReader.class);
        PolicyEvaluationRepository policyEvaluationRepository = mock(PolicyEvaluationRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        InfracostContextExtractor extractor = new InfracostContextExtractor(storageTypeService, objectMapper);

        prCommentService = new PrCommentService(
                gitHubWebhookService,
                gitLabWebhookService,
                bitBucketWebhookService,
                jobRepository,
                stepRepository,
                storageTypeService,
                stepOutputReader,
                objectMapper,
                policyEvaluationRepository,
                extractor
        );
    }

    @Test
    void postPlanResultIncludesInfracostSummaryAndReadsStorageOnce() {
        Job job = githubPrJob(777, JobStatus.completed);
        when(stepRepository.findByJobId(777)).thenReturn(List.of());
        when(jobRepository.findFirstByWorkspaceAndPrNumberAndIdNotAndAutoApplyFalseAndPrCommentIdIsNotNullOrderByIdDesc(
                any(), eq(12), eq(777))).thenReturn(Optional.empty());
        when(storageTypeService.getContext(777)).thenReturn("""
                {
                  "infracost/step-1": {
                    "status": "SUCCESS",
                    "currency": "USD",
                    "totalMonthlyCost": 165.20,
                    "pastTotalMonthlyCost": 120.00,
                    "diffTotalMonthlyCost": 45.20,
                    "shareUrl": "https://dashboard.infracost.io/share/demo",
                    "summary": {
                      "totalDetectedResources": 12,
                      "totalSupportedResources": 10,
                      "totalUnsupportedResources": 1,
                      "totalNoPriceResources": 1
                    }
                  }
                }
                """);

        prCommentService.postPlanResult(job);

        ArgumentCaptor<String> commentCaptor = ArgumentCaptor.forClass(String.class);
        verify(gitHubWebhookService).postPrComment(eq(job), commentCaptor.capture());
        // Once in fetchPlanDiffText + once in formatPlanComment (shared across structured changes, cost, and policy).
        verify(storageTypeService, times(2)).getContext(777);
        String markdown = commentCaptor.getValue();

        assertThat(markdown)
                .contains("### 💰 Cost Estimation (Infracost)")
                .contains("$120.00")
                .contains("$165.20")
                .contains("+$45.20 (+37.7%)")
                .contains("10 / 12")
                .contains("2 unpriced/unsupported")
                .contains("pre-deployment estimates")
                .contains("https://dashboard.infracost.io/share/demo");
    }

    @Test
    void renderCostEstimationSummaryFencesMultilineErrorsSafely() {
        Job job = githubPrJob(778, JobStatus.completed);
        when(storageTypeService.getContext(778)).thenReturn("""
                {
                  "infracost": {
                    "status": "ERROR",
                    "errorMessage": "Infracost API rate limit exceeded\\n</details>\\n# Injected Heading\\n@everyone ```rm -rf```"
                  }
                }
                """);

        Optional<String> rendered = prCommentService.renderCostEstimationSummary(job);

        assertThat(rendered).isPresent();
        assertThat(rendered.get())
                .contains("Cost Estimation (Infracost)")
                .contains("```text\nInfracost API rate limit exceeded\n</details>\n# Injected Heading\n@everyone ` ` `rm -rf` ` `\n```")
                .doesNotContain("> : ");
    }

    @Test
    void renderCostEstimationSummaryOmitsSkippedOrPartialNonSuccessAndRejectsUntrustedShareUrl() {
        Job skippedJob = githubPrJob(779, JobStatus.completed);
        when(storageTypeService.getContext(779)).thenReturn("""
                {
                  "infracost": {
                    "status": "SKIPPED"
                  }
                }
                """);
        assertThat(prCommentService.renderCostEstimationSummary(skippedJob)).isEmpty();

        Job missingTotalJob = githubPrJob(780, JobStatus.completed);
        when(storageTypeService.getContext(780)).thenReturn("""
                {
                  "infracost": {
                    "status": "SUCCESS",
                    "currency": "USD"
                  }
                }
                """);
        assertThat(prCommentService.renderCostEstimationSummary(missingTotalJob)).isEmpty();

        Job untrustedUrlJob = githubPrJob(781, JobStatus.completed);
        when(storageTypeService.getContext(781)).thenReturn("""
                {
                  "infracost": {
                    "status": "SUCCESS",
                    "currency": "USD",
                    "totalMonthlyCost": 25.00,
                    "shareUrl": "https://evil.example.com/phish"
                  }
                }
                """);
        String rendered = prCommentService.renderCostEstimationSummary(untrustedUrlJob).orElseThrow();
        assertThat(rendered)
                .contains("| **—** | **$25.00** | **—** |")
                .doesNotContain("evil.example.com");
    }

    private static Job githubPrJob(int id, JobStatus status) {
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("simple-finops");

        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);

        Workspace ws = new Workspace();
        ws.setId(UUID.randomUUID());
        ws.setName("aws-cost-soft-fail");
        ws.setOrganization(org);
        ws.setVcs(vcs);

        Job job = new Job();
        job.setId(id);
        job.setPrNumber(12);
        job.setStatus(status);
        job.setWorkspace(ws);
        job.setOrganization(org);
        return job;
    }
}
