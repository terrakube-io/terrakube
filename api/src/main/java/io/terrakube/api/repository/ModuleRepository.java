package io.terrakube.api.repository;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.vcs.VcsType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import io.terrakube.api.rs.module.Module;

public interface ModuleRepository extends JpaRepository<Module, UUID> {

    List<Module> findByOrganizationId(UUID organizationId);
    List<Module> findByOrganizationIn(List<Organization> organizations);
    List<Module> findAllByOrganizationIdAndNameAndProvider(UUID organizationId, String name, String provider);

    // The module refresh poller (ModuleRefreshPollerJob) and its writes (ModuleRefreshTransactions).
    // next_refresh NULL means due now; a claimed module holds its lease expiry there, so every write of
    // a refresh result is keyed on that exact lease and becomes a no-op once another claim replaced it.

    // NULLS FIRST is spelled out: PostgreSQL sorts NULLs last by default, and new or changed modules
    // (NULL) should not wait behind the backlog.
    @Query("SELECT m.id FROM module m WHERE m.organization.disabled = false "
            + "AND (m.nextRefresh IS NULL OR m.nextRefresh <= :now) ORDER BY m.nextRefresh ASC NULLS FIRST")
    List<UUID> findDueIds(@Param("now") Date now, Pageable pageable);

    // Atomic claim: only one replica's UPDATE can match a due row, the others see 0.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE module m SET m.nextRefresh = :lease "
            + "WHERE m.id = :id AND (m.nextRefresh IS NULL OR m.nextRefresh <= :now)")
    int claim(@Param("id") UUID id, @Param("lease") Date lease, @Param("now") Date now);

    // Hands claimed modules back when they could not be queued, so they are due on the next tick.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE module m SET m.nextRefresh = :now WHERE m.id IN :ids AND m.nextRefresh = :lease")
    int releaseLeases(@Param("ids") List<UUID> ids, @Param("lease") Date lease, @Param("now") Date now);

    // First write of a successful refresh: takes the row lock and proves the lease is still ours, before
    // the caller reads and inserts versions. Neither this nor finishRefresh clears the persistence context:
    // the caller loads the module in between and keeps using it (Module has @DynamicUpdate, so saving it
    // later does not write these columns back). Refresh results never go through an entity save, which
    // would also move updatedDate on every refresh.
    @Modifying
    @Query("UPDATE module m SET m.lastRefresh = :now WHERE m.id = :id AND m.nextRefresh = :lease")
    int markRefreshed(@Param("id") UUID id, @Param("lease") Date lease, @Param("now") Date now);

    @Modifying
    @Query("UPDATE module m SET m.nextRefresh = :next, m.lastRefreshError = NULL, "
            + "m.lastTagDetectedAt = :lastTagDetectedAt WHERE m.id = :id")
    int finishRefresh(@Param("id") UUID id, @Param("next") Date next,
            @Param("lastTagDetectedAt") Date lastTagDetectedAt);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE module m SET m.nextRefresh = :next, m.lastRefresh = :now, m.lastRefreshError = :error "
            + "WHERE m.id = :id AND m.nextRefresh = :lease")
    int recordFailure(@Param("id") UUID id, @Param("lease") Date lease, @Param("next") Date next,
            @Param("now") Date now, @Param("error") String error);

    // Manual sync. A refresh still running under the old lease then writes nothing, and the module is
    // claimed again on the next tick.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE module m SET m.nextRefresh = NULL WHERE m.id = :id")
    int markDue(@Param("id") UUID id);

    // Webhook-triggered refresh (RepoWebhookService). No clearing: it runs inside webhook transactions that keep
    // using the entities they loaded, and no module entity is saved there.
    @Modifying
    @Query("UPDATE module m SET m.nextRefresh = NULL WHERE m.id IN :ids")
    int markDueIds(@Param("ids") List<UUID> ids);

    @Query("SELECT m.id, m.source, m.tagPrefix FROM module m WHERE m.organization.disabled = false")
    List<Object[]> findIdSourceAndTagPrefix();

    @Query("SELECT m FROM module m WHERE m.organization.disabled = false AND m.vcs.vcsType IN :types")
    List<Module> findAllWithVcsTypeIn(@Param("types") Set<VcsType> types);

    // Back the module.refresh.overdue.* gauges (ModuleRefreshMetrics). MIN skips NULLs, so modules that
    // are due because they are new or changed count with age 0.
    @Query("SELECT COUNT(m) FROM module m WHERE m.organization.disabled = false "
            + "AND (m.nextRefresh IS NULL OR m.nextRefresh <= :now)")
    long countOverdue(@Param("now") Date now);

    @Query("SELECT MIN(m.nextRefresh) FROM module m WHERE m.organization.disabled = false AND m.nextRefresh <= :now")
    Date findOldestOverdue(@Param("now") Date now);
}
