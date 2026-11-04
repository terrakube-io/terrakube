package io.terrakube.api.plugin.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspaceBranchesTest {

    @Test
    void theFirstEntryOfAListIsTheBranchRunsUse() {
        assertEquals("main", WorkspaceBranches.firstBranch("main"));
        assertEquals("main", WorkspaceBranches.firstBranch("main,develop"));
        assertEquals("main", WorkspaceBranches.firstBranch(" main , develop"));
    }

    @Test
    void noBranchIsAnEmptyOne() {
        assertEquals("", WorkspaceBranches.firstBranch(null));
        assertEquals("", WorkspaceBranches.firstBranch(""));
    }

    @Test
    void aHeadsOrTagsPrefixIsDroppedAndNothingElse() {
        assertEquals("main", WorkspaceBranches.shortName("refs/heads/main"));
        assertEquals("feature/x", WorkspaceBranches.shortName("refs/heads/feature/x"));
        assertEquals("feature/x", WorkspaceBranches.shortName("feature/x"));
        assertEquals("v1.0.0", WorkspaceBranches.shortName("refs/tags/v1.0.0"));
        assertEquals("v1.0.0", WorkspaceBranches.shortName(" v1.0.0 "));
        assertEquals("", WorkspaceBranches.shortName(null));
    }

    @Test
    void remoteContentIsTheBranchOfUploadedContent() {
        assertEquals("remote-content", WorkspaceBranches.REMOTE_CONTENT);
    }

    @Test
    void matchesConfiguredBranchAllowsUnsetOrRemoteContentWorkspaceBranch() {
        org.junit.jupiter.api.Assertions.assertTrue(WorkspaceBranches.matchesConfiguredBranch(null, "main"));
        org.junit.jupiter.api.Assertions.assertTrue(WorkspaceBranches.matchesConfiguredBranch("", "main"));
        org.junit.jupiter.api.Assertions.assertTrue(WorkspaceBranches.matchesConfiguredBranch("   ", "main"));
        org.junit.jupiter.api.Assertions.assertTrue(WorkspaceBranches.matchesConfiguredBranch("remote-content", "main"));
    }

    @Test
    void matchesConfiguredBranchAllowsUnsetTargetBranch() {
        org.junit.jupiter.api.Assertions.assertTrue(WorkspaceBranches.matchesConfiguredBranch("main", null));
        org.junit.jupiter.api.Assertions.assertTrue(WorkspaceBranches.matchesConfiguredBranch("main", ""));
        org.junit.jupiter.api.Assertions.assertTrue(WorkspaceBranches.matchesConfiguredBranch("main", "   "));
    }

    @Test
    void matchesConfiguredBranchChecksExactAndPrefixNormalizedNames() {
        org.junit.jupiter.api.Assertions.assertTrue(WorkspaceBranches.matchesConfiguredBranch("main", "main"));
        org.junit.jupiter.api.Assertions.assertTrue(WorkspaceBranches.matchesConfiguredBranch("refs/heads/main", "main"));
        org.junit.jupiter.api.Assertions.assertTrue(WorkspaceBranches.matchesConfiguredBranch("main", "refs/heads/main"));
        org.junit.jupiter.api.Assertions.assertTrue(WorkspaceBranches.matchesConfiguredBranch("main, develop", "develop"));
        org.junit.jupiter.api.Assertions.assertTrue(WorkspaceBranches.matchesConfiguredBranch("main, develop", "refs/heads/develop"));
        org.junit.jupiter.api.Assertions.assertFalse(WorkspaceBranches.matchesConfiguredBranch("main", "develop"));
        org.junit.jupiter.api.Assertions.assertFalse(WorkspaceBranches.matchesConfiguredBranch("main, staging", "develop"));
    }
}
