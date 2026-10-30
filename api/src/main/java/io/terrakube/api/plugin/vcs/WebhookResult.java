package io.terrakube.api.plugin.vcs;

import io.terrakube.api.plugin.execution.WorkspaceBranchPolicy;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Getter
@Setter
@ToString
@Slf4j
public class WebhookResult {
    private String workspaceId;
    private String branch;
    private boolean isValid;
    private String event;
    private String createdBy;
    private String via;
    private List<String> fileChanges;
    private String commit;
    private Number prNumber;
    private boolean isRelease;
    private String commentBody;
    private String commentCommand;
    private String commentId;
    private boolean isPrComment;
    private String prFilesUrl;
    private String rawPayload;
    private String prDetailsUrl;

    /** What started the run, for the workspace branch policy. Not a getter, so it is not serialized. */
    public WorkspaceBranchPolicy.Source branchPolicySource() {
        if (isRelease) {
            return WorkspaceBranchPolicy.Source.RELEASE;
        }
        boolean pullRequest = isPrComment || prNumber != null
                || "pull_request".equals(event) || "merge_request".equals(event);
        return pullRequest ? WorkspaceBranchPolicy.Source.PULL_REQUEST : WorkspaceBranchPolicy.Source.PUSH;
    }

    public String getNormalizedEvent() {
        String normalizedEvent = "unknown";
        if (this.event != null) {
            switch (this.event) {
                case "push":
                    normalizedEvent = "push";
                    break;
                case "pull_request":
                case "merge_request":
                    normalizedEvent = "pull_request";
                    break;
                case "issue_comment":
                case "note":
                    normalizedEvent = "pr_comment";
                    break;
                case "release":
                    normalizedEvent = "release";
                    break;
                default:
                    normalizedEvent = "unknown";
                    break;
            }
        }
        log.info("Current event {} Normalized event: {}", this.event, normalizedEvent);
        return normalizedEvent;
    }
}
