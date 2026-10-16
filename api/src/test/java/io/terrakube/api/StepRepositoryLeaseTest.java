package io.terrakube.api;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.DeliveryState;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class StepRepositoryLeaseTest extends ServerApplicationTests {

    private Job createTestJob() {
        Organization organization = organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace workspace = workspaceRepository
                .findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();

        Job job = new Job();
        job.setStatus(JobStatus.completed);
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        return jobRepository.saveAndFlush(job);
    }

    @Test
    void stepEntityPersistsDeliveryFields() {
        Job job = createTestJob();

        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(1);
        step.setName("terraformPlan");
        step.setStatus(JobStatus.completed);
        step.setDeliveryState(DeliveryState.ACKED);
        step.setDeliveryRevision(12345L);
        long nowMs = System.currentTimeMillis();
        step.setDeliveryTimestamp(nowMs);
        step = stepRepository.saveAndFlush(step);

        Step loaded = stepRepository.findById(step.getId()).orElseThrow();
        assertThat(loaded.getDeliveryState()).isEqualTo(DeliveryState.ACKED);
        assertThat(loaded.getDeliveryRevision()).isEqualTo(12345L);
        assertThat(loaded.getDeliveryTimestamp()).isEqualTo(nowMs);
    }

    @Test
    void findByDeliveryStateAndLeaseUntilBeforeReturnsOnlyUnleasedOrExpired() {
        Job job = createTestJob();
        Date now = new Date();

        // 1. Spooled with no lease
        Step unleased = new Step();
        unleased.setJob(job);
        unleased.setStepNumber(1);
        unleased.setName("step-unleased");
        unleased.setStatus(JobStatus.completed);
        unleased.setDeliveryState(DeliveryState.SPOOLED);
        unleased = stepRepository.saveAndFlush(unleased);

        // 2. Spooled with expired lease
        Step expiredLease = new Step();
        expiredLease.setJob(job);
        expiredLease.setStepNumber(2);
        expiredLease.setName("step-expired");
        expiredLease.setStatus(JobStatus.completed);
        expiredLease.setDeliveryState(DeliveryState.SPOOLED);
        expiredLease.setLeaseOwner("other-pod");
        expiredLease.setLeaseUntil(new Date(now.getTime() - 60_000));
        expiredLease = stepRepository.saveAndFlush(expiredLease);

        // 3. Spooled with active lease
        Step activeLease = new Step();
        activeLease.setJob(job);
        activeLease.setStepNumber(3);
        activeLease.setName("step-active");
        activeLease.setStatus(JobStatus.completed);
        activeLease.setDeliveryState(DeliveryState.SPOOLED);
        activeLease.setLeaseOwner("active-pod");
        activeLease.setLeaseUntil(new Date(now.getTime() + 60_000));
        activeLease = stepRepository.saveAndFlush(activeLease);

        // 4. Already ACKED step
        Step acked = new Step();
        acked.setJob(job);
        acked.setStepNumber(4);
        acked.setName("step-acked");
        acked.setStatus(JobStatus.completed);
        acked.setDeliveryState(DeliveryState.ACKED);
        acked = stepRepository.saveAndFlush(acked);

        List<Step> results = stepRepository.findByDeliveryStateAndLeaseUntilBefore(
                DeliveryState.SPOOLED, now, PageRequest.of(0, 50));

        assertThat(results).extracting(Step::getId)
                .contains(unleased.getId(), expiredLease.getId())
                .doesNotContain(activeLease.getId(), acked.getId());
    }

    @Test
    void acquireLeaseIsAtomicAndPreventsConcurrentAcquisitions() {
        Job job = createTestJob();
        Date now = new Date();

        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(1);
        step.setName("step-atomic");
        step.setStatus(JobStatus.completed);
        step.setDeliveryState(DeliveryState.SPOOLED);
        step = stepRepository.saveAndFlush(step);

        Date leaseExpiration = new Date(now.getTime() + 120_000);

        // First attempt succeeds
        int claimed1 = stepRepository.acquireLease(step.getId(), "pod-1", leaseExpiration, now);
        assertThat(claimed1).isEqualTo(1);

        // Second attempt with active lease fails
        int claimed2 = stepRepository.acquireLease(step.getId(), "pod-2", leaseExpiration, now);
        assertThat(claimed2).isEqualTo(0);

        Step current = stepRepository.findById(step.getId()).orElseThrow();
        assertThat(current.getLeaseOwner()).isEqualTo("pod-1");

        // After lease expiry, new owner can acquire
        Date later = new Date(leaseExpiration.getTime() + 1000);
        Date newLeaseExpiration = new Date(later.getTime() + 120_000);
        int claimed3 = stepRepository.acquireLease(step.getId(), "pod-3", newLeaseExpiration, later);
        assertThat(claimed3).isEqualTo(1);

        Step afterExpired = stepRepository.findById(step.getId()).orElseThrow();
        assertThat(afterExpired.getLeaseOwner()).isEqualTo("pod-3");
    }

    @Test
    void updateDeliveryStateSetsStateAndClearsLease() {
        Job job = createTestJob();
        Date now = new Date();

        Step step = new Step();
        step.setJob(job);
        step.setStepNumber(1);
        step.setName("step-recover");
        step.setStatus(JobStatus.completed);
        step.setDeliveryState(DeliveryState.SPOOLED);
        step.setLeaseOwner("recovery-pod");
        step.setLeaseUntil(new Date(now.getTime() + 60_000));
        step = stepRepository.saveAndFlush(step);

        int updated = stepRepository.updateDeliveryState(step.getId(), DeliveryState.RECOVERED);
        assertThat(updated).isEqualTo(1);

        Step reloaded = stepRepository.findById(step.getId()).orElseThrow();
        assertThat(reloaded.getDeliveryState()).isEqualTo(DeliveryState.RECOVERED);
        assertThat(reloaded.getLeaseOwner()).isNull();
        assertThat(reloaded.getLeaseUntil()).isNull();
    }
}
