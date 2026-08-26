package io.terrakube.api.plugin.importer.tfcloud;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ImportedSensitiveVariable {
    private String sourceVariableId;
    private String value;
}
