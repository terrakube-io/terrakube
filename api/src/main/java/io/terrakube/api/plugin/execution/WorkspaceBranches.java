package io.terrakube.api.plugin.execution;

/**
 * How a workspace names the branch its runs use. One place for the rule, so the executor and the branch policy
 * agree on which branch a run without an explicit one takes.
 */
public final class WorkspaceBranches {

    /** The branch of a workspace, or of a job, that runs uploaded content instead of a repository. */
    public static final String REMOTE_CONTENT = "remote-content";

    private static final String HEADS_PREFIX = "refs/heads/";

    private WorkspaceBranches() {
    }

    /** The branch runs use when none is given: the first entry of the workspace branch, which can be a list. */
    public static String firstBranch(String workspaceBranch) {
        return workspaceBranch == null ? "" : workspaceBranch.split(",")[0].trim();
    }

    /** The branch name without a leading {@code refs/heads/}, so the two spellings of a branch compare equal. */
    public static String shortName(String branch) {
        return branch.startsWith(HEADS_PREFIX) ? branch.substring(HEADS_PREFIX.length()) : branch;
    }
}
