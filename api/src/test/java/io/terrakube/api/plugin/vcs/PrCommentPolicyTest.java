package io.terrakube.api.plugin.vcs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.logs.StepOutputReader;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.vcs.provider.bitbucket.BitBucketWebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GithubCommitStatus;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitlabCommitStatus;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.PolicyEvaluationRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.policy.PolicyEvaluation;
import io.terrakube.api.rs.policy.PolicyEvaluationStatus;
import io.terrakube.api.rs.policy.PolicyOverride;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrCommentPolicyTest {

    @Mock
    private GitHubWebhookService gitHubWebhookService;
    @Mock
    private GitLabWebhookService gitLabWebhookService;
    @Mock
    private BitBucketWebhookService bitBucketWebhookService;
    @Mock
    private JobRepository jobRepository;
    @Mock
    private StepRepository stepRepository;
    @Mock
    private StorageTypeService storageTypeService;
    @Mock
    private StepOutputReader stepOutputReader;
    @Mock
    private PolicyEvaluationRepository policyEvaluationRepository;

    private ObjectMapper objectMapper;
    private PrCommentService prCommentService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        prCommentService = new PrCommentService(
                gitHubWebhookService,
                gitLabWebhookService,
                bitBucketWebhookService,
                jobRepository,
                stepRepository,
                storageTypeService,
                stepOutputReader,
                objectMapper,
                policyEvaluationRepository
        );
        ReflectionTestUtils.setField(prCommentService, "uiUrl", "https://terrakube.io");
    }

    private Job createTestJob(VcsType vcsType) {
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("enterprise");

        Vcs vcs = new Vcs();
        vcs.setVcsType(vcsType);
        vcs.setApiUrl("https://api.github.com");

        Workspace ws = new Workspace();
        ws.setId(UUID.randomUUID());
        ws.setName("production-cluster");
        ws.setOrganization(org);
        ws.setVcs(vcs);

        Job job = new Job();
        job.setId(456);
        job.setWorkspace(ws);
        job.setCommitId("a1b2c3d4e5f6");
        job.setPrNumber(12);
        return job;
    }

    @Test
    void testPolicyGuardrailsSummaryWithExemption() {
        Job job = createTestJob(VcsType.GITHUB);

        String contextJson = """
                {
                  "policyEvaluation": {
                    "status": "PASSED",
                    "passedRules": 12,
                    "warningRules": 1,
                    "softMandatoryViolations": 0,
                    "hardMandatoryViolations": 0,
                    "results": [
                      {
                        "policySetName": "azure-networking",
                        "enforcementLevel": "hard-mandatory",
                        "status": "PASSED",
                        "exemptedViolations": [
                          {
                            "ruleId": "azure_apim_no_public_network",
                            "address": "azurerm_api_management.partner_gateway",
                            "ticketReference": "SEC-8842",
                            "expiresAt": "2026-12-31T00:00:00.000Z",
                            "justification": "Approved third-party integration gateway for Partner Corp"
                          }
                        ]
                      },
                      {
                        "policySetName": "enterprise-blast-radius",
                        "enforcementLevel": "advisory",
                        "status": "WARNING",
                        "violations": [
                          {
                            "ruleId": "storage_deletion_check",
                            "address": "azurerm_storage_account.sa",
                            "message": "Deleting storage account"
                          }
                        ]
                      }
                    ]
                  }
                }
                """;

        when(storageTypeService.getContext(456)).thenReturn(contextJson);
        when(policyEvaluationRepository.findByJob(job)).thenReturn(List.of());

        Optional<String> summaryOpt = prCommentService.renderPolicyGuardrailsSummary(job);
        assertTrue(summaryOpt.isPresent());
        String summary = summaryOpt.get();

        assertTrue(summary.contains("### 🛡️ Terrakube Policy Guardrails: PASSED (WITH EXEMPTION)"));
        assertTrue(summary.contains("| Policy Set | Status / Severity | Resource | Details / Justification |"));
        assertTrue(summary.contains("`azure-networking`"));
        assertTrue(summary.contains("🛡️ **EXEMPTED**"));
        assertTrue(summary.contains("`azurerm_api_management.partner_gateway`"));
        assertTrue(summary.contains("Rule: `azure_apim_no_public_network`"));
        assertTrue(summary.contains("Ticket: **SEC-8842**"));
        assertTrue(summary.contains("(Expires: 2026-12-31)"));
        assertTrue(summary.contains("_Approved third-party integration gateway for Partner Corp_"));

        assertTrue(summary.contains("`enterprise-blast-radius`"));
        assertTrue(summary.contains("ℹ️ Advisory"));
        assertTrue(summary.contains("`azurerm_storage_account.sa`"));
        assertTrue(summary.contains("Deleting storage account"));

        assertTrue(summary.contains("[View Full Policy Evaluation in Terrakube](https://terrakube.io/organizations/"));
    }

    @Test
    void testPolicyGuardrailsSummaryFailedHardMandatory() {
        Job job = createTestJob(VcsType.GITHUB);

        String contextJson = """
                {
                  "policyEvaluation": {
                    "status": "FAILED",
                    "passedRules": 5,
                    "warningRules": 0,
                    "softMandatoryViolations": 0,
                    "hardMandatoryViolations": 1,
                    "results": [
                      {
                        "policySetName": "security-baseline",
                        "enforcementLevel": "hard-mandatory",
                        "status": "FAILED",
                        "violations": [
                          {
                            "ruleId": "no_public_ssh",
                            "address": "azurerm_network_security_rule.allow_ssh",
                            "message": "Inbound SSH port 22 open to 0.0.0.0/0"
                          }
                        ]
                      }
                    ]
                  }
                }
                """;

        when(storageTypeService.getContext(456)).thenReturn(contextJson);
        when(policyEvaluationRepository.findByJob(job)).thenReturn(List.of());

        Optional<String> summaryOpt = prCommentService.renderPolicyGuardrailsSummary(job);
        assertTrue(summaryOpt.isPresent());
        String summary = summaryOpt.get();

        assertTrue(summary.contains("### 🛡️ Terrakube Policy Guardrails: FAILED"));
        assertTrue(summary.contains("❌ **FAILED** (Hard)"));
        assertTrue(summary.contains("`azurerm_network_security_rule.allow_ssh`"));
        assertTrue(summary.contains("Inbound SSH port 22 open to 0.0.0.0/0"));
    }

    @Test
    void testPolicyCommitStatusGitHubExemptedSuccess() {
        Job job = createTestJob(VcsType.GITHUB);

        String contextJson = """
                {
                  "policyEvaluation": {
                    "status": "PASSED",
                    "hardMandatoryViolations": 0,
                    "softMandatoryViolations": 0,
                    "results": [
                      {
                        "exemptedViolations": [
                          {
                            "ruleId": "test_rule"
                          }
                        ]
                      }
                    ]
                  }
                }
                """;

        when(policyEvaluationRepository.findByJob(job)).thenReturn(List.of());
        when(storageTypeService.getContext(456)).thenReturn(contextJson);

        prCommentService.sendPolicyCommitStatus(job);

        verify(gitHubWebhookService).sendCommitStatus(
                eq(job),
                eq("terrakube/policy-check"),
                eq(GithubCommitStatus.success),
                argThat(desc -> desc.contains("exemptions"))
        );
        verify(gitLabWebhookService, never()).sendCommitStatus(any(), any(), any(), any());
    }

    @Test
    void testPolicyCommitStatusGitLabFailed() {
        Job job = createTestJob(VcsType.GITLAB);

        PolicyEvaluation eval = new PolicyEvaluation();
        eval.setStatus(PolicyEvaluationStatus.FAILED);
        eval.setHardMandatoryViolations(2);
        eval.setSoftMandatoryViolations(1);

        when(policyEvaluationRepository.findByJob(job)).thenReturn(List.of(eval));

        prCommentService.sendPolicyCommitStatus(job);

        verify(gitLabWebhookService).sendCommitStatus(
                eq(job),
                eq("terrakube/policy-check"),
                eq(GitlabCommitStatus.failed),
                argThat(desc -> desc.contains("failed") && desc.contains("2 hard"))
        );
        verify(gitHubWebhookService, never()).sendCommitStatus(any(), any(), any(), any());
    }

    @Test
    void testPolicyCommitStatusSoftMandatoryOverridden() {
        Job job = createTestJob(VcsType.GITHUB);

        PolicyEvaluation eval = new PolicyEvaluation();
        eval.setStatus(PolicyEvaluationStatus.PASSED);
        eval.setHardMandatoryViolations(0);
        eval.setSoftMandatoryViolations(1);

        PolicyOverride override = new PolicyOverride();
        override.setJustification("Urgent hotfix approved by SecOps");
        eval.setOverride(override);

        when(policyEvaluationRepository.findByJob(job)).thenReturn(List.of(eval));

        prCommentService.sendPolicyCommitStatus(job);

        verify(gitHubWebhookService).sendCommitStatus(
                eq(job),
                eq("terrakube/policy-check"),
                eq(GithubCommitStatus.success),
                argThat(desc -> desc.contains("overridden"))
        );
    }
}
