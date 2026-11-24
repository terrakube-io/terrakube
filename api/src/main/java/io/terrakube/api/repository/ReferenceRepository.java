package io.terrakube.api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import io.terrakube.api.rs.collection.Collection;
import io.terrakube.api.rs.collection.Reference;
import io.terrakube.api.rs.collection.ReferenceScope;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.workspace.Workspace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReferenceRepository extends JpaRepository<Reference, UUID> {

    Optional<List<Reference>> findByWorkspace(Workspace workspace);

    boolean existsByWorkspaceAndCollection(Workspace workspace, Collection collection);

    /** Another reference that already attaches the collection to the project. */
    boolean existsByCollectionAndProjectAndIdNot(Collection collection, Project project, UUID id);

    /** Another reference that already attaches the collection to a whole organization. */
    boolean existsByCollectionAndScopeAndIdNot(Collection collection, ReferenceScope scope, UUID id);

    // ExecutorService.loadDefault runs after ScheduleJob has deliberately released its transaction
    // before making external calls (see ScheduleJob's class comment) - Collection.item is a lazy
    // @OneToMany with no session left open by the time loadDefault reads collection.getItem(), so a
    // plain findByWorkspace() throws LazyInitializationException. LEFT JOIN FETCH (not an inner
    // join) so a Collection with zero items doesn't silently drop its Reference from the result;
    // DISTINCT because the *-to-many fetch produces one row per item, which would otherwise return
    // duplicate Reference objects for any collection with more than one item.
    @Query("SELECT DISTINCT r FROM reference r "
            + "JOIN FETCH r.collection c "
            + "LEFT JOIN FETCH c.item "
            + "WHERE r.workspace = :workspace")
    List<Reference> findByWorkspaceWithCollectionItems(@Param("workspace") Workspace workspace);

    // Same fetch shape as findByWorkspaceWithCollectionItems, for project and organization scoped references.
    @Query("SELECT DISTINCT r FROM reference r "
            + "JOIN FETCH r.collection c "
            + "LEFT JOIN FETCH c.item "
            + "WHERE r.scope = :scope AND r.project = :project")
    List<Reference> findByProjectWithCollectionItems(@Param("project") Project project,
            @Param("scope") ReferenceScope scope);

    @Query("SELECT DISTINCT r FROM reference r "
            + "JOIN FETCH r.collection c "
            + "LEFT JOIN FETCH c.item "
            + "WHERE r.scope = :scope AND c.organization = :organization")
    List<Reference> findByOrganizationWithCollectionItems(@Param("organization") Organization organization,
            @Param("scope") ReferenceScope scope);

    @Query(value = "select count(r.id) from reference r where r.project_id = :projectId", nativeQuery = true)
    long countByProjectId(@Param("projectId") String projectId);
}
