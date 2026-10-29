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
    void aHeadsPrefixIsDroppedAndNothingElse() {
        assertEquals("main", WorkspaceBranches.shortName("refs/heads/main"));
        assertEquals("feature/x", WorkspaceBranches.shortName("refs/heads/feature/x"));
        assertEquals("feature/x", WorkspaceBranches.shortName("feature/x"));
        assertEquals("refs/tags/v1", WorkspaceBranches.shortName("refs/tags/v1"));
    }

    @Test
    void remoteContentIsTheBranchOfUploadedContent() {
        assertEquals("remote-content", WorkspaceBranches.REMOTE_CONTENT);
    }
}
