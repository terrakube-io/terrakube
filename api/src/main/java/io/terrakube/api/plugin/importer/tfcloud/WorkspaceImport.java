package io.terrakube.api.plugin.importer.tfcloud;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkspaceImport {
    private WorkspaceData data;

    @Getter
    @Setter
    public static class WorkspaceData {
        private String id;
        private String type;
        private WorkspaceAttributes attributes;

        @Getter
        @Setter
        public static class WorkspaceAttributes {
            private String name;
            private String description;
            @JsonProperty("working-directory")
            private String workingDirectory;
            @JsonProperty("vcs-repo")
            private VcsRepo vcsRepo;
            @JsonProperty("terraform-version")
            private String terraformVersion;
            @JsonProperty("execution-mode")
            private String executionMode;
        }

        @Getter
        @Setter
        public static class VcsRepo {
            private String identifier;
            @JsonProperty("service-provider")
            private String serviceProvider;
            @JsonProperty("repository-http-url")
            private String repositoryHttpUrl;
            private String branch;
        }
    }
}
