package io.terrakube.api.repository;

import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.policy.PolicyEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyEvaluationRepository extends JpaRepository<PolicyEvaluation, UUID> {
    List<PolicyEvaluation> findByJob(Job job);

    Optional<PolicyEvaluation> findByJobAndStep(Job job, Step step);

    @Query("SELECT DISTINCT pe.storageUri FROM policy_evaluation pe WHERE pe.createdDate < :cutoff AND pe.storageUri IS NOT NULL AND pe.storageUri != ''")
    List<String> findStorageUrisByCreatedDateBefore(@Param("cutoff") Date cutoff);

    @Modifying
    @Transactional
    @Query("DELETE FROM policy_evaluation pe WHERE pe.createdDate < :cutoff")
    int deleteByCreatedDateBefore(@Param("cutoff") Date cutoff);
}
