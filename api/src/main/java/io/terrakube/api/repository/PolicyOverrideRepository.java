package io.terrakube.api.repository;

import io.terrakube.api.rs.policy.PolicyEvaluation;
import io.terrakube.api.rs.policy.PolicyOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

public interface PolicyOverrideRepository extends JpaRepository<PolicyOverride, UUID> {
    Optional<PolicyOverride> findByEvaluation(PolicyEvaluation evaluation);

    @Modifying
    @Transactional
    @Query("DELETE FROM policy_override po WHERE po.createdDate < :cutoff")
    int deleteByCreatedDateBefore(@Param("cutoff") Date cutoff);
}
