package io.terrakube.api.plugin.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(WorkspaceBranches.matchesConfiguredBranch(null, "main"));
        assertTrue(WorkspaceBranches.matchesConfiguredBranch("", "main"));
        assertTrue(WorkspaceBranches.matchesConfiguredBranch("   ", "main"));
        assertTrue(WorkspaceBranches.matchesConfiguredBranch("remote-content", "main"));
    }

    @Test
    void matchesConfiguredBranchFailsClosedOnUnsetOrBlankTargetBranchWhenConfigured() {
        assertFalse(WorkspaceBranches.matchesConfiguredBranch("main", null));
        assertFalse(WorkspaceBranches.matchesConfiguredBranch("main", ""));
        assertFalse(WorkspaceBranches.matchesConfiguredBranch("main", "   "));
    }

    @Test
    void matchesConfiguredBranchChecksExactAndPrefixNormalizedNames() {
        assertTrue(WorkspaceBranches.matchesConfiguredBranch("main", "main"));
        assertTrue(WorkspaceBranches.matchesConfiguredBranch("refs/heads/main", "main"));
        assertTrue(WorkspaceBranches.matchesConfiguredBranch("main", "refs/heads/main"));
        assertTrue(WorkspaceBranches.matchesConfiguredBranch("main, develop", "develop"));
        assertTrue(WorkspaceBranches.matchesConfiguredBranch("main, develop", "refs/heads/develop"));
        assertFalse(WorkspaceBranches.matchesConfiguredBranch("main", "develop"));
        assertFalse(WorkspaceBranches.matchesConfiguredBranch("main, staging", "develop"));
    }
}
