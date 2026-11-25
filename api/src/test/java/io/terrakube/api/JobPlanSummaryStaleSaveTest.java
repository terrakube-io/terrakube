package io.terrakube.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;

/**
 * The plan counts are written with a single update, but the scheduler loads a job at the start of a tick and saves it
 * later in the same tick. That save must not write back the counts the job had when it was loaded, or a summary that
 * was recorded in between is lost for good.
 */
class JobPlanSummaryStaleSaveTest extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String WORKSPACE_ID = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";

    private Job job;

    @BeforeEach
    void aJob() {
        Organization organization = organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow();
        Workspace workspace = workspaceRepository.findById(UUID.fromString(WORKSPACE_ID)).orElseThrow();
        Job newJob = new Job();
        newJob.setStatus(JobStatus.running);
        newJob.setOrganization(organization);
        newJob.setWorkspace(workspace);
        job = jobRepository.saveAndFlush(newJob);
    }

    @AfterEach
    void removeTheJob() {
        jobRepository.deleteById(job.getId());
    }

    @Test
    void savingAJobLoadedBeforeTheSummaryWasRecordedKeepsTheSummary() {
        Job loadedByTheScheduler = jobRepository.findById(job.getId()).orElseThrow();

        jobRepository.updatePlanSummary(job.getId(), 3, 2, 1);

        loadedByTheScheduler.setStatus(JobStatus.waitingApproval);
        jobRepository.saveAndFlush(loadedByTheScheduler);

        Job reread = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reread.getStatus()).isEqualTo(JobStatus.waitingApproval);
        assertThat(reread.getPlanAdd()).isEqualTo(3);
        assertThat(reread.getPlanChange()).isEqualTo(2);
        assertThat(reread.getPlanDestroy()).isEqualTo(1);
    }
}
