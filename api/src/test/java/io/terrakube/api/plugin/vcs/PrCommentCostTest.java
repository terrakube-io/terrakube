package io.terrakube.api.plugin.vcs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.logs.StepOutputReader;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.vcs.provider.bitbucket.BitBucketWebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.repository.JobRepository;
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
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PrCommentCostTest {

    private GitHubWebhookService gitHubWebhookService;
    private JobRepository jobRepository;
    private StepRepository stepRepository;
    private StorageTypeService storageTypeService;
    private PrCommentService prCommentService;

    @BeforeEach
    void setUp() {
        gitHubWebhookService = Mockito.mock(GitHubWebhookService.class);
        GitLabWebhookService gitLabWebhookService = Mockito.mock(GitLabWebhookService.class);
        BitBucketWebhookService bitBucketWebhookService = Mockito.mock(BitBucketWebhookService.class);
        jobRepository = Mockito.mock(JobRepository.class);
        stepRepository = Mockito.mock(StepRepository.class);
        storageTypeService = Mockito.mock(StorageTypeService.class);
        StepOutputReader stepOutputReader = Mockito.mock(StepOutputReader.class);

        prCommentService = new PrCommentService(
                gitHubWebhookService,
                gitLabWebhookService,
                bitBucketWebhookService,
                jobRepository,
                stepRepository,
                storageTypeService,
                stepOutputReader,
                new ObjectMapper()
        );
    }

    @Test
    void postPlanResultIncludesInfracostSummaryCoverageAndDisclaimer() {
        Job job = githubPrJob(777, JobStatus.completed);
        when(stepRepository.findByJobId(777)).thenReturn(List.of());
        when(jobRepository.findFirstByWorkspaceAndPrNumberAndIdNotAndAutoApplyFalseAndPrCommentIdIsNotNullOrderByIdDesc(
                any(), eq(12), eq(777))).thenReturn(Optional.empty());
        when(storageTypeService.getContext(777)).thenReturn("""
                {
                  "infracost": {
                    "status": "SUCCESS",
                    "currency": "USD",
                    "totalMonthlyCost": "165.20",
                    "pastTotalMonthlyCost": "120.00",
                    "diffTotalMonthlyCost": "45.20",
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
    void renderCostEstimationSummaryHandlesErrorStateGracefully() {
        Job job = githubPrJob(778, JobStatus.completed);
        when(storageTypeService.getContext(778)).thenReturn("""
                {
                  "infracost": {
                    "status": "ERROR",
                    "errorMessage": "Infracost API rate limit exceeded"
                  }
                }
                """);

        Optional<String> rendered = prCommentService.renderCostEstimationSummary(job);

        assertThat(rendered).isPresent();
        assertThat(rendered.get())
                .contains("Cost Estimation (Infracost)")
                .contains("Infracost API rate limit exceeded");
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
