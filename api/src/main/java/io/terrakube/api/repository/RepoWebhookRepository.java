package io.terrakube.api.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import io.terrakube.api.rs.webhook.RepoWebhook;

public interface RepoWebhookRepository extends JpaRepository<RepoWebhook, UUID> {
    Optional<RepoWebhook> findByRepositoryUrl(String repositoryUrl);
}
