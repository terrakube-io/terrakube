package io.terrakube.api.plugin.scheduler.job.tcl.executor;

import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CommitBranchToRequireTest {

    private Workspace workspace;

    @BeforeEach
    void setUp() {
        workspace = new Workspace();
        workspace.setBranch("main");
        workspace.setRestrictApplyToConfiguredBranch(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"terraformApply", "terraformDestroy"})
    void anApplyOrDestroyStepMustRunACommitOfTheConfiguredBranch(String flowType) {
        assertThat(ExecutorService.commitBranchToRequire(workspace, flowType, "main")).isEqualTo("main");
    }

    @ParameterizedTest
    @ValueSource(strings = {"terraformPlan", "terraformPlanDestroy", "approval", "customScripts", "policyEvaluation"})
    void otherStepsAreNotChecked(String flowType) {
        assertThat(ExecutorService.commitBranchToRequire(workspace, flowType, "main")).isNull();
    }

    @Test
    void nothingIsRequiredWhenTheWorkspaceDoesNotRestrictApply() {
        workspace.setRestrictApplyToConfiguredBranch(false);

        assertThat(ExecutorService.commitBranchToRequire(workspace, "terraformApply", "main")).isNull();
    }

    @Test
    void uploadedContentHasNoRepositoryToCheck() {
        assertThat(ExecutorService.commitBranchToRequire(workspace, "terraformApply", "remote-content")).isNull();
    }

    @Test
    void theConfiguredBranchIsTheFirstEntryOfTheWorkspaceBranch() {
        workspace.setBranch(" refs/heads/release , main");

        assertThat(ExecutorService.commitBranchToRequire(workspace, "terraformApply", "release")).isEqualTo("release");
    }
}
