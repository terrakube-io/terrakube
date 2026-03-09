package io.terrakube.api.repository;

import io.terrakube.api.rs.webhook.RepoWebhook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RepoWebhookRepository extends JpaRepository<RepoWebhook, UUID> {

    Optional<RepoWebhook> findByRepositoryUrl(String repositoryUrl);
}
