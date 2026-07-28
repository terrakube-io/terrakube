package io.terrakube.api.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import io.terrakube.api.rs.webhook.RepoWebhook;

public interface RepoWebhookRepository extends JpaRepository<RepoWebhook, UUID> {
    Optional<RepoWebhook> findByRepositoryUrl(String repositoryUrl);

    // Transaction-scoped advisory lock keyed by the repository URL, used to
    // serialize concurrent getOrCreateRepoWebhook calls for the same repo.
    // Released automatically when the calling transaction commits or rolls
    // back.
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(?1))", nativeQuery = true)
    void acquireRepoWebhookLock(String repositoryUrl);
}
