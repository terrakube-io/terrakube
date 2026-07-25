package io.terrakube.api.plugin.vcs.discovery;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class VcsGroupSummary {
    private String id;
    private String name;
}
