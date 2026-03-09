package io.terrakube.api.repository;

import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.webhook.VcsWebhook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VcsWebhookRepository extends JpaRepository<VcsWebhook, UUID> {

    Optional<VcsWebhook> findByVcsAndRepositoryUrl(Vcs vcs, String repositoryUrl);
}
