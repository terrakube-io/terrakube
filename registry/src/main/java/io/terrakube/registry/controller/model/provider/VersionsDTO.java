package io.terrakube.registry.controller.model.provider;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class VersionsDTO {
    List<VersionDTO> versions;

    // Printed by terraform init for the whole provider (the protocol has no per-version deprecation).
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    List<String> warnings;
}
