package io.terrakube.api.plugin.importer.tfcloud;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkspaceImportRequest {
    private String id;
    private String organizationId;
    private String organization;
    private String vcsId;
    private String branch;
    private String folder;
    private String terraformVersion;
    private String name;
    private String source;
    private String description;
    private String executionMode;
    private List<String> variableCollectionIds;
    private List<ImportedSensitiveVariable> sensitiveVariables;
}
