package io.terrakube.api.plugin.scheduler.inactive;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Date;
import java.util.Optional;

import org.apache.commons.lang3.time.DateUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;

class InactiveJobsPropertiesTest {

    private final Date now = new Date();
    private final InactiveJobsProperties properties = new InactiveJobsProperties();

    private Job job(JobStatus status, int createdHoursAgo) {
        Job job = new Job();
        job.setStatus(status);
        job.setCreatedDate(DateUtils.addHours(now, -createdHoursAgo));
        job.setUpdatedDate(DateUtils.addHours(now, -createdHoursAgo));
        return job;
    }

    @Test
    void aRunWaitingForApprovalNeverExpiresByDefault() {
        assertThat(properties.expiryOf(job(JobStatus.waitingApproval, 24 * 30), now)).isEmpty();
    }

    @Test
    void aRunWaitingForApprovalIsCancelledAfterTheOptInTimeoutCountedFromWhenItStartedWaiting() {
        properties.setWaitingApprovalTimeoutHours(48);
        Job job = job(JobStatus.waitingApproval, 100); // created long ago
        job.setUpdatedDate(DateUtils.addHours(now, -47)); // but waiting for 47 hours only

        assertThat(properties.expiryOf(job, now)).isEmpty();

        job.setUpdatedDate(DateUtils.addHours(now, -49));
        Optional<InactiveJobsProperties.Expiry> expiry = properties.expiryOf(job, now);
        assertThat(expiry).isPresent();
        assertThat(expiry.get().finalStatus()).isEqualTo(JobStatus.cancelled);
        assertThat(expiry.get().reason()).contains("Approval was not given within 48 hours");
    }

    @Test
    void anApprovalWaitFallsBackToTheCreatedDateWhenThereIsNoUpdatedDate() {
        properties.setWaitingApprovalTimeoutHours(1);
        Job job = job(JobStatus.waitingApproval, 2);
        job.setUpdatedDate(null);

        assertThat(properties.expiryOf(job, now)).isPresent();
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"pending", "queue", "running", "approved"})
    void aStuckRunIsFailedAfterTheMaxAge(JobStatus status) {
        assertThat(properties.expiryOf(job(status, 5), now)).isEmpty();

        Optional<InactiveJobsProperties.Expiry> expiry = properties.expiryOf(job(status, 7), now);
        assertThat(expiry).isPresent();
        assertThat(expiry.get().finalStatus()).isEqualTo(JobStatus.failed);
        assertThat(expiry.get().reason()).contains("6 hours");
    }

    @Test
    void theMaxAgeIsConfigurable() {
        properties.setMaxAgeHours(12);

        assertThat(properties.expiryOf(job(JobStatus.running, 7), now)).isEmpty();
        assertThat(properties.expiryOf(job(JobStatus.running, 13), now)).isPresent();
    }

    @Test
    void timeSpentWaitingForApprovalDoesNotCountAgainstTheMaxAgeOnceTheRunIsApproved() {
        Job job = job(JobStatus.running, 30); // created 30 hours ago, waited for approval
        job.setApprovedAt(DateUtils.addHours(now, -1)); // approved an hour ago

        assertThat(properties.expiryOf(job, now)).isEmpty();

        job.setApprovedAt(DateUtils.addHours(now, -7));
        assertThat(properties.expiryOf(job, now)).isPresent();
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"completed", "failed", "cancelled", "rejected", "noChanges", "notExecuted"})
    void aFinishedRunNeverExpires(JobStatus status) {
        assertThat(properties.expiryOf(job(status, 1000), now)).isEmpty();
    }

    @Test
    void applyToSetsTheStatusAndRecordsTheReasonInTheOutput() {
        Job job = job(JobStatus.pending, 7);
        job.setOutput("Step 1 completed");

        properties.expiryOf(job, now).orElseThrow().applyTo(job);

        assertThat(job.getStatus()).isEqualTo(JobStatus.failed);
        assertThat(job.getOutput()).isEqualTo("Step 1 completed\n"
                + "Closed by Terrakube: The run did not finish within 6 hours and was closed.");
    }

    @Test
    void applyToKeepsTheOutputWithinTheColumnLength() {
        Job job = job(JobStatus.pending, 7);
        job.setOutput("x".repeat(1024));

        properties.expiryOf(job, now).orElseThrow().applyTo(job);

        assertThat(job.getOutput()).hasSizeLessThanOrEqualTo(1024);
        assertThat(job.getOutput()).endsWith("was closed.");
    }
}
