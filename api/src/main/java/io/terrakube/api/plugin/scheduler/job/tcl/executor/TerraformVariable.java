package io.terrakube.api.plugin.scheduler.job.tcl.executor;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@ToString
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TerraformVariable {

    private String key;
    private String value;
    private boolean hcl;

}
