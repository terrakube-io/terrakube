package io.terrakube.api.repository;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.workspace.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    Workspace getByOrganizationNameAndName(String organizationName, String workspaceName);

    Optional<List<Workspace>> findWorkspacesByOrganizationNameAndNameStartingWith(String organizationName, String workspaceNameStartingWidth);

    Optional<List<Workspace>> findWorkspacesByOrganization(Organization organization);

}
