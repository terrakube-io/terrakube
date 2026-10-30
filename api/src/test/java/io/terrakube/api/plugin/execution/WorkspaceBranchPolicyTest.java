package io.terrakube.api.plugin.execution;

import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class WorkspaceBranchPolicyTest {
    private static final String PLAN_ONLY = "plan-only-template";
    private static final String APPLY = "apply-template";

    private TclService tclService;
    private WorkspaceBranchPolicy policy;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        tclService = Mockito.mock(TclService.class);
        when(tclService.isTemplatePlanOnly(PLAN_ONLY)).thenReturn(true);
        when(tclService.isTemplatePlanOnly(APPLY)).thenReturn(false);
        policy = new WorkspaceBranchPolicy(tclService);

        workspace = new Workspace();
        workspace.setName("prod");
        workspace.setBranch("main");
        workspace.setRestrictApplyToConfiguredBranch(true);
    }

    @Test
    void theSettingIsOffByDefault() {
        assertEquals(false, new Workspace().isRestrictApplyToConfiguredBranch());
    }

    @Test
    void settingOffAllowsAnyBranchTemplateAndCommit() {
        workspace.setRestrictApplyToConfiguredBranch(false);

        assertTrue(policy.violation(workspace, "feature/x", "0123456789abcdef0123456789abcdef01234567", APPLY).isEmpty());
    }

    @Test
    void aPlanOnlyTemplateRunsOnAnyBranchAndCommit() {
        assertTrue(policy.violation(workspace, "feature/x", null, PLAN_ONLY).isEmpty());
        assertTrue(policy.violation(workspace, "feature/x", "0123456789abcdef0123456789abcdef01234567", PLAN_ONLY).isEmpty());
    }

    @Test
    void aTemplateThatChangesInfrastructureIsRefusedOnAnotherBranch() {
        String message = policy.violation(workspace, "feature/x", null, APPLY).orElseThrow();

        assertTrue(message.contains("prod"), message);
        assertTrue(message.contains("other than main"), message);
        assertTrue(message.contains("feature/x"), message);
    }

    @Test
    void aTemplateThatChangesInfrastructureRunsOnTheConfiguredBranch() {
        assertTrue(policy.violation(workspace, "main", null, APPLY).isEmpty());
        assertTrue(policy.violation(workspace, "refs/heads/main", null, APPLY).isEmpty());
        assertTrue(policy.violation(workspace, " main ", null, APPLY).isEmpty());
    }

    @Test
    void aWorkspaceOnATagRunsThatTagWhicheverWayItIsSpelled() {
        workspace.setBranch("v1.0.0");

        assertTrue(policy.violation(workspace, "refs/tags/v1.0.0", null, APPLY).isEmpty());
        assertTrue(policy.violation(workspace, "v1.0.0", null, APPLY).isEmpty());
        assertTrue(policy.violation(workspace, "refs/tags/v2.0.0", null, APPLY).isPresent());
    }

    @Test
    void noBranchMeansTheConfiguredBranch() {
        assertTrue(policy.violation(workspace, null, null, APPLY).isEmpty());
        assertTrue(policy.violation(workspace, " ", null, APPLY).isEmpty());
    }

    @Test
    void aCommitIdIsRefusedForATemplateThatChangesInfrastructure() {
        // The executor fetches the commit by id, so it could belong to another branch.
        String message = policy.violation(workspace, "main", "0123456789abcdef0123456789abcdef01234567", APPLY)
                .orElseThrow();

        assertTrue(message.contains("commit id"), message);
        assertTrue(policy.violation(workspace, null, "0123456789abcdef0123456789abcdef01234567", APPLY).isPresent());
    }

    @Test
    void aBlankCommitIdIsTheSameAsNone() {
        assertTrue(policy.violation(workspace, "main", " ", APPLY).isEmpty());
    }

    @Test
    void onlyTheFirstEntryOfAListOfBranchesIsTheConfiguredBranch() {
        workspace.setBranch("main, develop");

        assertTrue(policy.violation(workspace, "main", null, APPLY).isEmpty());
        assertTrue(policy.violation(workspace, "develop", null, APPLY).isPresent());
        assertTrue(policy.violation(workspace, null, null, APPLY).isEmpty());
    }

    @Test
    void remoteContentOnAWorkspaceWithAVcsBranchIsRefusedForApplyButNotForPlan() {
        assertTrue(policy.violation(workspace, "remote-content", null, APPLY).isPresent());
        assertTrue(policy.violation(workspace, "remote-content", null, PLAN_ONLY).isEmpty());
    }

    @Test
    void aWorkspaceWithoutAVcsIsNotAffected() {
        workspace.setBranch("remote-content");

        assertTrue(policy.violation(workspace, "remote-content", null, APPLY).isEmpty());
        assertTrue(policy.violation(workspace, null, null, APPLY).isEmpty());
    }

    @Test
    void aTemplateThatCannotBeLoadedCountsAsOneThatChangesInfrastructure() {
        when(tclService.isTemplatePlanOnly("missing")).thenReturn(false);

        assertTrue(policy.violation(workspace, "feature/x", null, "missing").isPresent());
        assertTrue(policy.violation(workspace, "feature/x", null, null).isPresent());
    }

    @Test
    void branchNamesAreCaseSensitive() {
        assertTrue(policy.violation(workspace, "Main", null, APPLY).isPresent());
    }
}
