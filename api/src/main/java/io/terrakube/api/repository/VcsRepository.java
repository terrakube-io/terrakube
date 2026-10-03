package io.terrakube.api.repository;

import io.terrakube.api.rs.vcs.Vcs;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VcsRepository extends JpaRepository<Vcs, UUID> {
    Vcs findByCallback(String callback);
    
    Vcs findFirstByClientId(String clientId);

    List<Vcs> findByClientId(String clientId);
}
