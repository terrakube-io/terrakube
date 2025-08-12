
package io.terrakube.api.plugin.vcs.provider.gitlab;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitlabReleaseModel {
    private Long id;

    @JsonProperty("created_at")
    private String createdAt;

    private String description;
    private String name;

    @JsonProperty("released_at")
    private String releasedAt;

    private String tag;

    @JsonProperty("object_kind")
    private String objectKind;

    private Project project;
    private String url;
    private String action;
    private Assets assets;
    private Commit commit;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Project {
        private Long id;
        private String name;
        private String description;

        @JsonProperty("web_url")
        private String webUrl;

        @JsonProperty("avatar_url")
        private String avatarUrl;

        @JsonProperty("git_ssh_url")
        private String gitSshUrl;

        @JsonProperty("git_http_url")
        private String gitHttpUrl;

        private String namespace;

        @JsonProperty("visibility_level")
        private Integer visibilityLevel;

        @JsonProperty("path_with_namespace")
        private String pathWithNamespace;

        @JsonProperty("default_branch")
        private String defaultBranch;

        @JsonProperty("ci_config_path")
        private String ciConfigPath;

        private String homepage;
        private String url;

        @JsonProperty("ssh_url")
        private String sshUrl;

        @JsonProperty("http_url")
        private String httpUrl;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Assets {
        private Integer count;
        private List<Link> links;
        private List<Source> sources;

        @Getter
        @Setter
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Link {
            // This would contain any custom links added to the release
            private String name;
            private String url;
        }

        @Getter
        @Setter
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Source {
            private String format;
            private String url;
        }
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Commit {
        private String id;
        private String message;
        private String title;
        private String timestamp;
        private String url;
        private Author author;

        @Getter
        @Setter
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Author {
            private String name;
            private String email;
        }
    }
}