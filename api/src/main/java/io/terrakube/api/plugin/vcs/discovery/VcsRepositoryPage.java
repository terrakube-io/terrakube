package io.terrakube.api.plugin.vcs.discovery;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class VcsRepositoryPage {
    private List<VcsRepositorySummary> items;
    private boolean hasMore;
    private int page;
}
