package io.terrakube.api.plugin.importer.tfcloud;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SensitiveVariableImportPreview {
    private String id;
    private String key;
    private String description;
    private String category;
    private boolean hcl;
}
