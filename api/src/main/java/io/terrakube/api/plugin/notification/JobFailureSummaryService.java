package io.terrakube.api.plugin.notification;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import io.terrakube.api.plugin.logs.StepOutputReader;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;

import lombok.extern.slf4j.Slf4j;

// Builds the short "why did this run fail" blurb for a failure notification. There is no
// failure-reason field on Job (job.output only ever holds vestigial "Step <id> completed"
// markers - see the executor's UpdateJobStatusImpl), so this reads the console output of the
// step that actually failed - the same source the job-details UI and PrCommentService use - and
// returns a trimmed tail of it.
@Slf4j
@Service
public class JobFailureSummaryService {

    private static final int MAX_LENGTH = 500;

    private final StepRepository stepRepository;
    private final StepOutputReader stepOutputReader;

    public JobFailureSummaryService(StepRepository stepRepository, StepOutputReader stepOutputReader) {
        this.stepRepository = stepRepository;
        this.stepOutputReader = stepOutputReader;
    }

    // Never throws: a failure notification must still go out even when the run output can't be
    // read back. Returns null when there is nothing useful to show.
    public String describeFailure(Job job) {
        try {
            Step failedStep = pickFailedStep(stepRepository.findByJobId(job.getId()));
            if (failedStep == null) {
                return null;
            }
            String output = stepOutputReader.read(job, failedStep);
            if (output == null || output.isBlank()) {
                return null;
            }
            output = output.strip();
            return output.length() > MAX_LENGTH
                    ? output.substring(output.length() - MAX_LENGTH).strip()
                    : output;
        } catch (Exception e) {
            log.warn("Could not build failure summary for job {}: {}", job.getId(), e.getMessage());
            return null;
        }
    }

    // The step actually marked failed, if any; otherwise the last step that ran. A job can reach
    // "failed" before any step is marked failed (e.g. a dispatch error), and a custom TCL
    // template can append steps after the terraform step - so "highest step number" is the best
    // fallback, not a guaranteed terraform step.
    private Step pickFailedStep(List<Step> steps) {
        return steps.stream()
                .filter(step -> step.getStatus() == JobStatus.failed)
                .max(Comparator.comparingInt(Step::getStepNumber))
                .or(() -> steps.stream().max(Comparator.comparingInt(Step::getStepNumber)))
                .orElse(null);
    }
}
