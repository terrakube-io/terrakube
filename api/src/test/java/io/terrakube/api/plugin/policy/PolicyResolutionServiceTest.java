package io.terrakube.api.plugin.policy;

import io.terrakube.api.plugin.scheduler.job.tcl.executor.model.PolicyContext;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.model.PolicyExemptionContext;
import io.terrakube.api.plugin.vcs.TokenService;
import io.terrakube.api.repository.GlobalVarRepository;
import io.terrakube.api.repository.PolicyAttachmentRepository;
import io.terrakube.api.repository.PolicyExemptionRepository;
import io.terrakube.api.repository.PolicySetRepository;
import io.terrakube.api.repository.TagRepository;
import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.globalvar.Globalvar;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.policy.PolicyAttachment;
import io.terrakube.api.rs.policy.PolicyEnforcementLevel;
import io.terrakube.api.rs.policy.PolicyExemption;
import io.terrakube.api.rs.policy.PolicySet;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.tag.Tag;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.parameters.Category;
import io.terrakube.api.rs.workspace.parameters.Variable;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class PolicyResolutionServiceTest {

    private PolicySetRepository policySetRepository;
    private PolicyAttachmentRepository policyAttachmentRepository;
    private PolicyExemptionRepository policyExemptionRepository;
    private GlobalVarRepository globalVarRepository;
    private VariableRepository variableRepository;
    private TagRepository tagRepository;
    private TokenService tokenService;

    private PolicyResolutionService policyResolutionService;

    @BeforeEach
    void setUp() {
        policySetRepository = Mockito.mock(PolicySetRepository.class);
        policyAttachmentRepository = Mockito.mock(PolicyAttachmentRepository.class);
        policyExemptionRepository = Mockito.mock(PolicyExemptionRepository.class);
        globalVarRepository = Mockito.mock(GlobalVarRepository.class);
        variableRepository = Mockito.mock(VariableRepository.class);
        tagRepository = Mockito.mock(TagRepository.class);
        tokenService = Mockito.mock(TokenService.class);

        policyResolutionService = new PolicyResolutionService(
                policySetRepository,
                policyAttachmentRepository,
                policyExemptionRepository,
                globalVarRepository,
                variableRepository,
                tagRepository,
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

        PolicySet policy = new PolicySet();
        policy.setId(UUID.randomUUID());
        policy.setName("Security-Audit");
        when(policySetRepository.findByOrganizationAndGlobalTrue(org)).thenReturn(List.of(policy));

        // Org globalvars
        Globalvar gvPolicy = new Globalvar();
        gvPolicy.setCategory(Category.POLICY);
        gvPolicy.setKey("max_deletions");
        gvPolicy.setValue("5");
        gvPolicy.setSensitive(false);

        Globalvar gvSensitivePolicy = new Globalvar();
        gvSensitivePolicy.setCategory(Category.POLICY);
        gvSensitivePolicy.setKey("secops_token");
        gvSensitivePolicy.setValue("secret123");
        gvSensitivePolicy.setSensitive(true); // Must be omitted!

        Globalvar gvTf = new Globalvar();
        gvTf.setCategory(Category.TERRAFORM);
        gvTf.setKey("region");
        gvTf.setValue("us-east-1");

        when(globalVarRepository.findByOrganization(org)).thenReturn(List.of(gvPolicy, gvSensitivePolicy, gvTf));

        // Workspace vars: overrides max_deletions to 10
        Variable wsPolicy = new Variable();
        wsPolicy.setCategory(Category.POLICY);
        wsPolicy.setKey("max_deletions");
        wsPolicy.setValue("10");
        wsPolicy.setSensitive(false);

        Variable wsCustom = new Variable();
        wsCustom.setCategory(Category.POLICY);
        wsCustom.setKey("allowed_locations");
        wsCustom.setValue("eastus,westus");
        wsCustom.setSensitive(false);

        when(variableRepository.findByWorkspace(ws)).thenReturn(Optional.of(List.of(wsPolicy, wsCustom)));

        List<PolicyContext> resolved = policyResolutionService.resolvePoliciesForJob(job);
        assertEquals(1, resolved.size());
        PolicyContext ctx = resolved.get(0);

        assertNotNull(ctx.getInputs());
        // max_deletions was overridden by workspace to 10
        assertEquals("10", ctx.getInputs().get("max_deletions"));
        assertEquals("eastus,westus", ctx.getInputs().get("allowed_locations"));
        // sensitive variable must NOT be present
        assertFalse(ctx.getInputs().containsKey("secops_token"));
        // terraform variable must NOT be present
        assertFalse(ctx.getInputs().containsKey("region"));
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
}
