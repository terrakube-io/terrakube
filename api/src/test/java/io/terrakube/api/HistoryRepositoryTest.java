package io.terrakube.api;

import io.terrakube.api.repository.HistoryRepository;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.history.History;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The two derived queries {@link RunTriggerDispatchServiceTest} exercises against mocks, against the real schema. */
@Transactional
class HistoryRepositoryTest extends ServerApplicationTests {

    private static final String WORKSPACE_SOURCE = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";

    @Autowired
    private HistoryRepository historyRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Leaves {@code id} unset so save() inserts rather than merges. {@code created_date} is
     * backfilled via a native update (two inserts can land on the same millisecond, and the
     * column is {@code updatable = false} anyway), with {@code entityManager.clear()} to avoid
     * returning a stale cached instance.
     */
    private History save(Workspace workspace, Instant createdAt) {
        History history = new History();
        history.setWorkspace(workspace);
        History saved = historyRepository.saveAndFlush(history);

        entityManager.createNativeQuery("UPDATE history SET created_date = ?1 WHERE id = ?2")
                .setParameter(1, Timestamp.from(createdAt))
                .setParameter(2, saved.getId().toString())
                .executeUpdate();
        entityManager.clear();

        return historyRepository.findById(saved.getId()).orElseThrow();
    }

    @Test
    void mostRecentAndMostRecentBeforeAnInstantAgreeOnOrdering() {
        Workspace workspace = workspaceRepository.findById(UUID.fromString(WORKSPACE_SOURCE)).orElseThrow();

        Instant now = Instant.now();
        History earlier = save(workspace, now.minusSeconds(60));
        History later = save(workspace, now);

        assertThat(later.getCreatedDate()).isAfter(earlier.getCreatedDate());

        Optional<History> mostRecent = historyRepository.findFirstByWorkspaceOrderByCreatedDateDesc(workspace);
        assertThat(mostRecent).map(History::getId).contains(later.getId());

        Optional<History> beforeLater = historyRepository
                .findFirstByWorkspaceAndCreatedDateLessThanOrderByCreatedDateDesc(workspace, later.getCreatedDate());
        assertThat(beforeLater).map(History::getId).contains(earlier.getId());

        Optional<History> beforeEarlier = historyRepository
                .findFirstByWorkspaceAndCreatedDateLessThanOrderByCreatedDateDesc(workspace, earlier.getCreatedDate());
        assertThat(beforeEarlier).isEmpty();
    }
}
