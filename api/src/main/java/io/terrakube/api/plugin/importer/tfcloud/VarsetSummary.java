package io.terrakube.api.plugin.importer.tfcloud;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class VarsetSummary {
    private String id;
    private String name;

    public VarsetSummary(String id, String name) {
        this.id = id;
        this.name = name;
    }
}
