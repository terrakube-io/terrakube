package io.terrakube.api.plugin.vcs.provider.gitlab;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitlabMergeRequestModel {
    @JsonProperty("object_kind")
    private String objectKind;

    @JsonProperty("object_attributes")
    private ObjectAttributes objectAttributes;

    private User user;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ObjectAttributes {
        private Long id;
        private Long iid;
        private String title;
        private String description;
        private String state;
        private String action;

        @JsonProperty("source_branch")
        private String sourceBranch;

        @JsonProperty("target_branch")
        private String targetBranch;

        @JsonProperty("last_commit")
        private LastCommit lastCommit;

        @JsonProperty("merge_status")
        private String mergeStatus;

        @JsonProperty("work_in_progress")
        private boolean workInProgress;

        private String url;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LastCommit {
        private String id;
        private String message;
        private String url;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class User {
        private String name;
        private String username;
        private String email;
    }
}