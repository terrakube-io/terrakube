package io.terrakube.api.plugin.scheduler.inactive;

import java.util.Date;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.time.DateUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

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

    // job.output is a varchar(1024)
    private static final int MAX_OUTPUT_LENGTH = 1024;

    /**
     * Hours a pending, queue, approved or running run may stay open before it is failed. Counted
     * from the run's creation, or from its approval when it was approved by a person, so time
     * spent waiting for approval never counts against it.
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
            String existing = job.getOutput() == null ? "" : job.getOutput();
            int room = MAX_OUTPUT_LENGTH - line.length() - 1;
            if (existing.length() > room) {
                existing = room > 0 ? existing.substring(existing.length() - room) : "";
            }
            job.setOutput(existing.isEmpty() ? line : existing + "\n" + line);
        }
    }

    /** The closing status and reason when the run is past its limit at {@code now}, else empty. */
    public Optional<Expiry> expiryOf(Job job, Date now) {
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
        if (start == null || !now.after(DateUtils.addHours(start, maxAgeHours))) {
            return Optional.empty();
        }
        return Optional.of(new Expiry(JobStatus.failed,
                "The run did not finish within " + maxAgeHours + " hours and was closed."));
    }
}
