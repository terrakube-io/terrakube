package io.terrakube.api.plugin.policy;

import io.terrakube.api.plugin.scheduler.job.tcl.executor.model.PolicyContext;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.model.PolicyExemptionContext;
import io.terrakube.api.plugin.vcs.TokenService;
import io.terrakube.api.repository.PolicyAttachmentRepository;
import io.terrakube.api.repository.PolicyExemptionRepository;
import io.terrakube.api.repository.PolicySetParameterRepository;
import io.terrakube.api.repository.PolicySetRepository;
import io.terrakube.api.repository.TagRepository;
import io.terrakube.api.repository.WorkspaceTagRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.policy.PolicyAttachment;
import io.terrakube.api.rs.policy.PolicyEnforcementLevel;
import io.terrakube.api.rs.policy.PolicyExemption;
import io.terrakube.api.rs.policy.PolicySet;
import io.terrakube.api.rs.policy.PolicySetParameter;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.tag.Tag;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.tag.WorkspaceTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class PolicyResolutionServiceTest {

    private PolicySetRepository policySetRepository;
    private PolicyAttachmentRepository policyAttachmentRepository;
    private PolicyExemptionRepository policyExemptionRepository;
    private PolicySetParameterRepository policySetParameterRepository;
    private TagRepository tagRepository;
    private WorkspaceTagRepository workspaceTagRepository;
    private TokenService tokenService;

    private PolicyResolutionService policyResolutionService;

    @BeforeEach
    void setUp() {
        policySetRepository = Mockito.mock(PolicySetRepository.class);
        policyAttachmentRepository = Mockito.mock(PolicyAttachmentRepository.class);
        policyExemptionRepository = Mockito.mock(PolicyExemptionRepository.class);
        policySetParameterRepository = Mockito.mock(PolicySetParameterRepository.class);
        tagRepository = Mockito.mock(TagRepository.class);
        workspaceTagRepository = Mockito.mock(WorkspaceTagRepository.class);
        tokenService = Mockito.mock(TokenService.class);

        policyResolutionService = new PolicyResolutionService(
                policySetRepository,
                policyAttachmentRepository,
                policyExemptionRepository,
                policySetParameterRepository,
                tagRepository,
                workspaceTagRepository,
                tokenService
        );
    }

    @Test
    void testCumulativePolicyResolutionAndDeduplication() {
        Organization org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("AcmeOrg");

        Project project = new Project();
        project.setId(UUID.randomUUID());
        project.setName("Payments");

        Workspace ws = new Workspace();
        ws.setId(UUID.randomUUID());
        ws.setName("prod-api");
        ws.setOrganization(org);
        ws.setProject(project);

        UUID tagId = UUID.randomUUID();
        Tag tag = new Tag();
        tag.setId(tagId);
        tag.setName("env:prod");

        WorkspaceTag wt = new WorkspaceTag();
        wt.setTagId(tagId.toString());
        ws.setWorkspaceTag(List.of(wt));
        when(workspaceTagRepository.findByWorkspace(ws)).thenReturn(List.of(wt));

        Job job = new Job();
        job.setId(101);
        job.setOrganization(org);
        job.setWorkspace(ws);

        // 1. Global Policy
        PolicySet globalPolicy = new PolicySet();
        globalPolicy.setId(UUID.randomUUID());
        globalPolicy.setName("Global-Baseline");
        globalPolicy.setGlobal(true);
        globalPolicy.setEnforcementLevel(PolicyEnforcementLevel.HARD_MANDATORY);
        when(policySetRepository.findByOrganizationAndGlobalTrue(org)).thenReturn(List.of(globalPolicy));

        // 2. Project Policy
        PolicySet projectPolicy = new PolicySet();
        projectPolicy.setId(UUID.randomUUID());
        projectPolicy.setName("Project-PCI");
        projectPolicy.setEnforcementLevel(PolicyEnforcementLevel.HARD_MANDATORY);
        PolicyAttachment paProj = new PolicyAttachment();
        paProj.setPolicySet(projectPolicy);
        when(policyAttachmentRepository.findByProject(project)).thenReturn(List.of(paProj));

        // 3. Tag Policy
        PolicySet tagPolicy = new PolicySet();
        tagPolicy.setId(UUID.randomUUID());
        tagPolicy.setName("Tag-ProdGuardrails");
        tagPolicy.setEnforcementLevel(PolicyEnforcementLevel.SOFT_MANDATORY);
        PolicyAttachment paTag = new PolicyAttachment();
        paTag.setPolicySet(tagPolicy);
        when(tagRepository.findAllById(List.of(tagId))).thenReturn(List.of(tag));
        when(policyAttachmentRepository.findByTagIn(List.of(tag))).thenReturn(List.of(paTag));

        // 4. Workspace Policy (also references projectPolicy to test deduplication)
        PolicyAttachment paWs1 = new PolicyAttachment();
        paWs1.setPolicySet(projectPolicy); // Duplicate!

        PolicySet wsPolicy = new PolicySet();
        wsPolicy.setId(UUID.randomUUID());
        wsPolicy.setName("Workspace-Custom");
        wsPolicy.setEnforcementLevel(PolicyEnforcementLevel.ADVISORY);
        PolicyAttachment paWs2 = new PolicyAttachment();
        paWs2.setPolicySet(wsPolicy);

        when(policyAttachmentRepository.findByWorkspace(ws)).thenReturn(List.of(paWs1, paWs2));

        // Execute resolution
        List<PolicyContext> resolved = policyResolutionService.resolvePoliciesForJob(job);

        // Assert cumulative total is 4 (not 5, due to deduplication)
        assertNotNull(resolved);
        assertEquals(4, resolved.size());

        List<String> names = resolved.stream().map(PolicyContext::getPolicyName).toList();
        assertTrue(names.contains("Global-Baseline"));
        assertTrue(names.contains("Project-PCI"));
        assertTrue(names.contains("Tag-ProdGuardrails"));
        assertTrue(names.contains("Workspace-Custom"));
    }

    @Test
    void testPolicyVariableInjectionAndSensitivity() {
        Organization org = new Organization();
        org.setId(UUID.randomUUID());

        Workspace ws = new Workspace();
        ws.setId(UUID.randomUUID());
        ws.setOrganization(org);

        Job job = new Job();
        job.setId(200);
        job.setOrganization(org);
        job.setWorkspace(ws);

        PolicySet policy1 = new PolicySet();
        policy1.setId(UUID.randomUUID());
        policy1.setName("Security-Audit");

        PolicySet policy2 = new PolicySet();
        policy2.setId(UUID.randomUUID());
        policy2.setName("Tagging-Policy");

        when(policySetRepository.findByOrganizationAndGlobalTrue(org)).thenReturn(List.of(policy1, policy2));

        PolicySetParameter param1 = new PolicySetParameter();
        param1.setId(UUID.randomUUID());
        param1.setKey("max_deletions");
        param1.setValue("5");
        param1.setPolicySet(policy1);

        PolicySetParameter param2 = new PolicySetParameter();
        param2.setId(UUID.randomUUID());
        param2.setKey("allowed_locations");
        param2.setValue("eastus,westus");
        param2.setPolicySet(policy1);

        when(policySetParameterRepository.findByPolicySet(policy1)).thenReturn(List.of(param1, param2));

        PolicySetParameter tagParam = new PolicySetParameter();
        tagParam.setId(UUID.randomUUID());
        tagParam.setKey("required_tags");
        tagParam.setValue("Environment,Owner");
        tagParam.setPolicySet(policy2);

        when(policySetParameterRepository.findByPolicySet(policy2)).thenReturn(List.of(tagParam));

        List<PolicyContext> resolved = policyResolutionService.resolvePoliciesForJob(job);
        assertEquals(2, resolved.size());

        // Sorted by name: Security-Audit, Tagging-Policy
        PolicyContext ctx1 = resolved.get(0);
        assertEquals("Security-Audit", ctx1.getPolicyName());
        assertNotNull(ctx1.getInputs());
        assertEquals("5", ctx1.getInputs().get("max_deletions"));
        assertEquals("eastus,westus", ctx1.getInputs().get("allowed_locations"));
        assertFalse(ctx1.getInputs().containsKey("required_tags"), "Policy 1 must NOT contain Policy 2 parameters");

        PolicyContext ctx2 = resolved.get(1);
        assertEquals("Tagging-Policy", ctx2.getPolicyName());
        assertNotNull(ctx2.getInputs());
        assertEquals("Environment,Owner", ctx2.getInputs().get("required_tags"));
        assertFalse(ctx2.getInputs().containsKey("max_deletions"), "Policy 2 must NOT contain Policy 1 parameters");
    }

    @Test
    void testResolveExemptionsForJob() {
        Organization org = new Organization();
        org.setId(UUID.randomUUID());

        Workspace ws = new Workspace();
        ws.setId(UUID.randomUUID());

        Project proj = new Project();
        proj.setId(UUID.randomUUID());
        ws.setProject(proj);

        Job job = new Job();
        job.setId(300);
        job.setOrganization(org);
        job.setWorkspace(ws);

        PolicySet policySet = new PolicySet();
        policySet.setId(UUID.randomUUID());

        PolicyExemption pe = new PolicyExemption();
        pe.setId(UUID.randomUUID());
        pe.setPolicySet(policySet);
        pe.setRuleId("azure_apim_no_public_network");
        pe.setTicketReference("SEC-9988");
        pe.setJustification("Legacy billing integration migration in progress");
        pe.setExpiresAt(new Date(System.currentTimeMillis() + 86400000L));

        when(policyExemptionRepository.findActiveExemptionsForWorkspace(eq(org), eq(ws), eq(proj), any(Date.class)))
                .thenReturn(List.of(pe));

        List<PolicyExemptionContext> exemptions = policyResolutionService.resolveExemptionsForJob(job);
        assertNotNull(exemptions);
        assertEquals(1, exemptions.size());

        PolicyExemptionContext ec = exemptions.get(0);
        assertEquals("azure_apim_no_public_network", ec.getRuleId());
        assertEquals("SEC-9988", ec.getTicketReference());
        assertEquals("Legacy billing integration migration in progress", ec.getJustification());
    }

    @Test
    void testResolvePoliciesForJob_WithCustomOpaVersion() {
        Organization org = new Organization();
        org.setId(UUID.randomUUID());

        Workspace ws = new Workspace();
        ws.setId(UUID.randomUUID());

        Job job = new Job();
        job.setId(400);
        job.setOrganization(org);
        job.setWorkspace(ws);

        PolicySet customPolicy = new PolicySet();
        customPolicy.setId(UUID.randomUUID());
        customPolicy.setName("Custom-Opa-Policy");
        customPolicy.setOpaVersion("0.68.0");
        customPolicy.setEnforcementLevel(PolicyEnforcementLevel.HARD_MANDATORY);

        PolicySet defaultPolicy = new PolicySet();
        defaultPolicy.setId(UUID.randomUUID());
        defaultPolicy.setName("Default-Opa-Policy");
        defaultPolicy.setOpaVersion(null);
        defaultPolicy.setEnforcementLevel(PolicyEnforcementLevel.ADVISORY);

        PolicyAttachment pa1 = new PolicyAttachment();
        pa1.setPolicySet(customPolicy);
        PolicyAttachment pa2 = new PolicyAttachment();
        pa2.setPolicySet(defaultPolicy);

        when(policyAttachmentRepository.findByWorkspace(ws)).thenReturn(List.of(pa1, pa2));

        List<PolicyContext> resolved = policyResolutionService.resolvePoliciesForJob(job);
        assertNotNull(resolved);
        assertEquals(2, resolved.size());

        PolicyContext customCtx = resolved.stream().filter(p -> "Custom-Opa-Policy".equals(p.getPolicyName())).findFirst().orElseThrow();
        assertEquals("0.68.0", customCtx.getOpaVersion());

        PolicyContext defaultCtx = resolved.stream().filter(p -> "Default-Opa-Policy".equals(p.getPolicyName())).findFirst().orElseThrow();
        assertNull(defaultCtx.getOpaVersion());
    }
}
