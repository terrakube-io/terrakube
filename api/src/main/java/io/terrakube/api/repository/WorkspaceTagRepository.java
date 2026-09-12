package io.terrakube.api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.tag.WorkspaceTag;

import java.util.List;
import java.util.UUID;

public interface WorkspaceTagRepository extends JpaRepository<WorkspaceTag, UUID> {

    List<WorkspaceTag> findByWorkspace(Workspace workspace);

    WorkspaceTag getByWorkspaceAndTagId(Workspace workspace, String tagId);

    void deleteByWorkspace(Workspace workspace);
}
