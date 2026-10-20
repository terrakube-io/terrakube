package io.terrakube.api;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;

import static org.assertj.core.api.Assertions.assertThat;

// Not @Transactional, like the scheduler: ExecutorService runs after ScheduleJob has released its
// transaction, where reading the lazy Job.step collection throws LazyInitializationException, so the
// step count has to come from the repository.
class PlanArtifactsRepositoryTest extends ServerApplicationTests {

    @Autowired
    JobRepository jobRepository;

    @Autowired
    StepRepository stepRepository;

    @Autowired
    WorkspaceRepository workspaceRepository;

    @Autowired
    OrganizationRepository organizationRepository;

    private Job newJob() {
        Organization organization = organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace workspace = workspaceRepository
                .findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();
        Job job = new Job();
        job.setStatus(JobStatus.pending);
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        return jobRepository.saveAndFlush(job);
    }

    private void addStep(Job job, int number) {
        Step step = new Step();
        step.setStepNumber(number);
        step.setName("step " + number);
        step.setStatus(JobStatus.pending);
        step.setJob(job);
        stepRepository.saveAndFlush(step);
    }

    @Test
    void theStepCountIsReadWithoutASession() {
        Job job = newJob();
        assertThat(stepRepository.countByJobId(job.getId())).isZero();

        addStep(job, 100);
        assertThat(stepRepository.countByJobId(job.getId())).isEqualTo(1);

        addStep(job, 200);
        assertThat(stepRepository.countByJobId(job.getId())).isEqualTo(2);
    }

    @Test
    void clearingThePlanArtifactsClearsTheLocationAndTheChecksum() {
        Job job = newJob();
        job.setTerraformPlanArtifacts("https://bucket/plan-artifacts/org/ws/1/step/plan-artifacts.tar.gz");
        job.setTerraformPlanArtifactsChecksum("deadbeef");
        jobRepository.saveAndFlush(job);

        assertThat(jobRepository.clearPlanArtifactsLocation(job.getId())).isEqualTo(1);

        Job reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getTerraformPlanArtifacts()).isNull();
        assertThat(reloaded.getTerraformPlanArtifactsChecksum()).isNull();
    }
}
