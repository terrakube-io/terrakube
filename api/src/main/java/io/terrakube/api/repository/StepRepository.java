package io.terrakube.api.repository;

import io.terrakube.api.rs.job.step.DeliveryState;
import io.terrakube.api.rs.job.step.Step;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StepRepository extends JpaRepository<Step, UUID> {

    List<Step> findByJobId(int jobId);

    long countByJobId(int jobId);

    Optional<Step> findFirstByJobIdOrderByStepNumber(int jobId);

    @Query("SELECT s FROM step s WHERE s.deliveryState = :state AND (s.leaseUntil IS NULL OR s.leaseUntil < :now)")
    List<Step> findByDeliveryStateAndLeaseUntilBefore(
            @Param("state") DeliveryState state,
            @Param("now") Date now,
            Pageable pageable);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE step s SET s.leaseOwner = :owner, s.leaseUntil = :leaseUntil WHERE s.id = :stepId AND s.deliveryState = io.terrakube.api.rs.job.step.DeliveryState.SPOOLED AND (s.leaseUntil IS NULL OR s.leaseUntil < :now)")
    int acquireLease(
            @Param("stepId") UUID stepId,
            @Param("owner") String owner,
            @Param("leaseUntil") Date leaseUntil,
            @Param("now") Date now);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE step s SET s.leaseOwner = null, s.leaseUntil = null WHERE s.id = :stepId AND (:owner IS NULL OR s.leaseOwner = :owner)")
    int releaseLease(@Param("stepId") UUID stepId, @Param("owner") String owner);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE step s SET s.leaseOwner = null, s.leaseUntil = null WHERE s.id = :stepId")
    int releaseLease(@Param("stepId") UUID stepId);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE step s SET s.deliveryState = :newState, s.leaseOwner = null, s.leaseUntil = null WHERE s.id = :stepId AND (:owner IS NULL OR s.leaseOwner = :owner)")
    int updateDeliveryState(
            @Param("stepId") UUID stepId,
            @Param("newState") DeliveryState newState,
            @Param("owner") String owner);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE step s SET s.deliveryState = :newState, s.leaseOwner = null, s.leaseUntil = null WHERE s.id = :stepId")
    int updateDeliveryState(
            @Param("stepId") UUID stepId,
            @Param("newState") DeliveryState newState);
}

