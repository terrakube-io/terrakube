package io.terrakube.api.plugin.vcs.discovery;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class VcsRepositorySummary {
    private String name;
    private String fullName;
    private String group;
    private String url;
    private boolean privateRepo;
    private String defaultBranch;
}
