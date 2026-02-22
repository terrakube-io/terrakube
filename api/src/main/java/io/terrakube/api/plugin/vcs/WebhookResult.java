package io.terrakube.api.plugin.vcs;

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
    private boolean isPrComment;

    public String getNormalizedEvent() {
        String normalizedEvent = "";
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
        log.info("Current event {} Normalized event: {}", this.event, normalizedEvent);
        return normalizedEvent;
    }
}
