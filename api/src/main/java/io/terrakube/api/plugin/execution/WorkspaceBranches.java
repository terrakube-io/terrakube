package io.terrakube.api.plugin.execution;

/**
 * How a workspace names the branch its runs use. One place for the rule, so the executor and the branch policy
 * agree on which branch a run without an explicit one takes.
 */
public final class WorkspaceBranches {

    /** The branch of a workspace, or of a job, that runs uploaded content instead of a repository. */
    public static final String REMOTE_CONTENT = "remote-content";

    private static final String HEADS_PREFIX = "refs/heads/";
    private static final String TAGS_PREFIX = "refs/tags/";

    private WorkspaceBranches() {
    }

    /** The branch runs use when none is given: the first entry of the workspace branch, which can be a list. */
    public static String firstBranch(String workspaceBranch) {
        return workspaceBranch == null ? "" : workspaceBranch.split(",")[0].trim();
    }

    /**
     * The branch or tag name without a leading {@code refs/heads/} or {@code refs/tags/}, so the spellings of a
     * ref compare equal. The branch of a workspace can name a tag, and release webhooks send {@code refs/tags/...}.
     */
    public static String shortName(String ref) {
        if (ref == null) {
            return "";
        }
        String trimmed = ref.trim();
        if (trimmed.startsWith(HEADS_PREFIX)) {
            return trimmed.substring(HEADS_PREFIX.length());
        }
        if (trimmed.startsWith(TAGS_PREFIX)) {
            return trimmed.substring(TAGS_PREFIX.length());
        }
        return trimmed;
    }
}
