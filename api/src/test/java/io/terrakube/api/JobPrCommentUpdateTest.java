package io.terrakube.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;

/** The PR comment service stores its outcome with {@code JobRepository.updatePrComment}, never by saving a stale job. */
class JobPrCommentUpdateTest extends ServerApplicationTests {

    private Job job;

    @BeforeEach
    void aJob() {
        Organization organization = organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace workspace = workspaceRepository.findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();
        Job newJob = new Job();
        newJob.setStatus(JobStatus.completed);
        newJob.setOrganization(organization);
        newJob.setWorkspace(workspace);
        newJob.setPrNumber(7);
        job = jobRepository.saveAndFlush(newJob);
    }

    @Test
    void storesTheCommentIdAndTheError() {
        assertThat(jobRepository.updatePrComment(job.getId(), "12345", null)).isEqualTo(1);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getPrCommentId()).isEqualTo("12345");

        assertThat(jobRepository.updatePrComment(job.getId(), null, "Failed to post comment on pull request #7.")).isEqualTo(1);
        Job stored = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(stored.getPrCommentId()).isNull();
        assertThat(stored.getPrCommentError()).isEqualTo("Failed to post comment on pull request #7.");
    }

    @Test
    void leavesEveryOtherColumnAsItIsInTheDatabaseEvenWhenTheCallerHoldsAnOlderCopy() {
        Job stale = jobRepository.findById(job.getId()).orElseThrow();
        // someone changes the job while the comment is being posted
        Job changedMeanwhile = jobRepository.findById(job.getId()).orElseThrow();
        changedMeanwhile.setStatus(JobStatus.cancelled);
        jobRepository.saveAndFlush(changedMeanwhile);

        stale.setPrCommentId("999");
        jobRepository.updatePrComment(stale.getId(), stale.getPrCommentId(), stale.getPrCommentError());

        Job stored = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(stored.getPrCommentId()).isEqualTo("999");
        assertThat(stored.getStatus()).as("not overwritten by the stale copy's status").isEqualTo(JobStatus.cancelled);
    }

    @Test
    void doesNotWriteAJobThatHasBeenDeleted() {
        long before = jobRepository.count();
        jobRepository.deleteById(job.getId());
        jobRepository.flush();

        assertThat(jobRepository.updatePrComment(job.getId(), "12345", null)).isZero();

        assertThat(jobRepository.count()).isEqualTo(before - 1);
        assertThat(jobRepository.findById(job.getId())).isEmpty();
    }
}
