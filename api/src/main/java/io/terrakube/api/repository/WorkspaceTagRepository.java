package io.terrakube.api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.tag.WorkspaceTag;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface WorkspaceTagRepository extends JpaRepository<WorkspaceTag, UUID> {

    List<WorkspaceTag> findByWorkspace(Workspace workspace);

    WorkspaceTag getByWorkspaceAndTagId(Workspace workspace, String tagId);

    void deleteByWorkspace(Workspace workspace);

    /** The (workspace id, tag id) pairs that already exist for the given workspaces. */
    @Query("select wt.workspace.id, wt.tagId from workspacetag wt where wt.workspace.id in :workspaceIds")
    List<Object[]> findExistingPairs(@Param("workspaceIds") Collection<UUID> workspaceIds);
}
