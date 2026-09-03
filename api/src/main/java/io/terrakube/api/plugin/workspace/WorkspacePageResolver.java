package io.terrakube.api.plugin.workspace;

import com.yahoo.elide.core.security.User;
import lombok.AllArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.List;

@Controller
@AllArgsConstructor
public class WorkspacePageResolver {

    private final WorkspacePageService workspacePageService;

    @QueryMapping
    public WorkspacePageService.WorkspacePage workspacePage(
            @Argument String organizationId,
            @Argument Integer first,
            @Argument String after,
            @Argument String search,
            @Argument String status,
            @Argument List<String> tagIds,
            @Argument String projectId,
            @Argument WorkspacePageService.WorkspaceSort sort,
            Authentication authentication) {
        return workspacePageService.getPage(
                organizationId,
                first,
                after,
                search,
                status,
                tagIds,
                projectId,
                sort,
                new User((Principal) authentication));
    }
}
