package io.terrakube.api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.collection.Collection;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.workspace.Workspace;

import java.util.List;
import java.util.UUID;

public interface CollectionRepository extends JpaRepository<Collection, UUID> {

    // Every collection of the organization that applies to the workspace: global collections, collections
    // referencing the workspace and collections referencing its project (a null project matches no reference).
    // EXISTS keeps a collection that applies through several of these paths from being returned more than once.
    // Each path gets its own subquery: comparing r.workspace makes Hibernate join the workspace table (which
    // carries the soft-delete restriction), and in a shared subquery that join would drop project references.
    //
    // ExecutorService.loadDefault runs after ScheduleJob has deliberately released its transaction
    // before making external calls (see ScheduleJob's class comment) - Collection.item is a lazy
    // @OneToMany with no session left open by the time loadDefault reads collection.getItem(), so the
    // items are fetched here. LEFT JOIN FETCH (not an inner join) so a collection with zero items is still
    // returned; DISTINCT because the *-to-many fetch produces one row per item.
    @Query("SELECT DISTINCT c FROM collection c "
            + "LEFT JOIN FETCH c.item "
            + "WHERE c.organization = :organization "
            + "AND (c.global = true "
            + "OR EXISTS (SELECT r FROM reference r WHERE r.collection = c AND r.workspace = :workspace) "
            + "OR EXISTS (SELECT r FROM reference r WHERE r.collection = c AND r.project = :project))")
    List<Collection> findApplicableToWorkspaceWithItems(@Param("organization") Organization organization,
            @Param("workspace") Workspace workspace,
            @Param("project") Project project);
}
