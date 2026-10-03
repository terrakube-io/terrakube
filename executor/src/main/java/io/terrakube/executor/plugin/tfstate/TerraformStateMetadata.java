package io.terrakube.executor.plugin.tfstate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TerraformStateMetadata {
    @Builder.Default
    private int serial = 1;
    @Builder.Default
    private String lineage = "0";
    @Builder.Default
    private String md5 = "0";
}
