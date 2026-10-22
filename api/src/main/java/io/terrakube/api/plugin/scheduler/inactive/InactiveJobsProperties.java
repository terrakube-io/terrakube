package io.terrakube.api.plugin.scheduler.inactive;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.time.DateUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import lombok.Getter;
import lombok.Setter;

/**
 * Limits for runs that stay open for too long (#3727). A run that is stuck in pending, queue or
 * running (an executor died, a Quartz context was lost) is closed as failed after
 * {@link #maxAgeHours}. A run waiting for a human approval is not stuck, so it is never closed
 * unless {@link #waitingApprovalTimeoutHours} is set.
 */
@Component
@Getter
@Setter
@PropertySource(value = "classpath:application.properties", ignoreResourceNotFound = true)
@PropertySource(value = "classpath:application-${spring.profiles.active}.properties", ignoreResourceNotFound = true)
@ConfigurationProperties(prefix = "io.terrakube.scheduler.inactive-jobs")
public class InactiveJobsProperties {

    /** Prefix of the line recorded in the run's output, shown in the run details. */
    public static final String CLOSED_PREFIX = "Closed by Terrakube: ";

    private static final Set<JobStatus> OPEN_STATUSES =
            Set.of(JobStatus.pending, JobStatus.approved, JobStatus.queue, JobStatus.running);

    // The statuses of a run that is over, as the workspace queue sees it.
    private static final List<JobStatus> FINISHED_STATUSES = List.of(
            JobStatus.failed, JobStatus.completed, JobStatus.rejected, JobStatus.cancelled, JobStatus.noChanges);

    // job.output is a varchar(1024)
    private static final int MAX_OUTPUT_LENGTH = 1024;

    /**
     * Hours a pending, queue, approved or running run may stay open before it is failed. Counted
     * from the run's creation, or from its approval when it was approved by a person, so time
     * spent waiting for approval never counts against it. Time spent waiting behind an earlier
     * run of the same workspace does not count either.
     */
    private int maxAgeHours = 6;

    /**
     * Hours a run may wait for approval before it is cancelled, counted from when it started
     * waiting. 0 (the default) means approvals never time out.
     */
    private int waitingApprovalTimeoutHours = 0;

    /** Why and how a run is closed. */
    public record Expiry(JobStatus finalStatus, String reason) {

        /** Sets the closing status and records the reason in the run's output. */
        public void applyTo(Job job) {
            job.setStatus(finalStatus);
            String line = CLOSED_PREFIX + reason;
            if (line.length() > MAX_OUTPUT_LENGTH) {
                line = line.substring(0, MAX_OUTPUT_LENGTH);
            }
            String existing = job.getOutput() == null ? "" : job.getOutput();
            int room = MAX_OUTPUT_LENGTH - line.length() - 1;
            if (existing.length() > room) {
                existing = room > 0 ? existing.substring(existing.length() - room) : "";
            }
            job.setOutput(existing.isEmpty() ? line : existing + "\n" + line);
        }
    }

    /**
     * The closing status and reason when the run is past its limit at {@code now}, else empty. This does not
     * look at the other runs of the workspace; the schedulers use {@link #expiryOf(Job, Date, JobRepository)}.
     */
    public Optional<Expiry> expiryOf(Job job, Date now) {
        return expiryOf(job, now, (Date) null);
    }

    /**
     * Like {@link #expiryOf(Job, Date)}, for a run that may be waiting its turn. A workspace runs one job at a
     * time, so a run behind an unfinished one has not started and is not stuck: it is not closed while an earlier
     * run is unfinished (that run has its own limit), and the time it waited for its turn does not count, so it
     * is not closed the moment the run in front of it ends either.
     */
    public Optional<Expiry> expiryOf(Job job, Date now, JobRepository jobRepository) {
        Optional<Expiry> byAge = expiryOf(job, now, (Date) null);
        if (byAge.isEmpty() || jobRepository == null || job.getWorkspace() == null
                || (job.getStatus() != JobStatus.pending && job.getStatus() != JobStatus.queue)) {
            return byAge;
        }
        boolean waitingForAnEarlierRun = jobRepository
                .findByWorkspaceAndStatusNotInAndIdLessThan(job.getWorkspace(), FINISHED_STATUSES, job.getId())
                .map(earlier -> !earlier.isEmpty())
                .orElse(false);
        if (waitingForAnEarlierRun) {
            return Optional.empty();
        }
        Date turnCameAt = jobRepository.findFirstByWorkspaceAndIdLessThanOrderByUpdatedDateDesc(job.getWorkspace(), job.getId())
                .map(Job::getUpdatedDate)
                .orElse(null);
        return expiryOf(job, now, turnCameAt);
    }

    private Optional<Expiry> expiryOf(Job job, Date now, Date notBefore) {
        if (job.getStatus() == JobStatus.waitingApproval) {
            if (waitingApprovalTimeoutHours <= 0) {
                return Optional.empty();
            }
            Date waitingSince = job.getUpdatedDate() != null ? job.getUpdatedDate() : job.getCreatedDate();
            if (waitingSince == null || !now.after(DateUtils.addHours(waitingSince, waitingApprovalTimeoutHours))) {
                return Optional.empty();
            }
            return Optional.of(new Expiry(JobStatus.cancelled,
                    "Approval was not given within " + waitingApprovalTimeoutHours + " hours, the run was cancelled."));
        }

        if (!OPEN_STATUSES.contains(job.getStatus())) {
            // Finished runs never expire; a repeat pass over one must not turn it into a failure.
            return Optional.empty();
        }

        Date start = job.getApprovedAt() != null ? job.getApprovedAt() : job.getCreatedDate();
        if (start != null && notBefore != null && notBefore.after(start)) {
            start = notBefore;
        }
        if (start == null || !now.after(DateUtils.addHours(start, maxAgeHours))) {
            return Optional.empty();
        }
        return Optional.of(new Expiry(JobStatus.failed,
                "The run did not finish within " + maxAgeHours + " hours and was closed."));
    }
}
