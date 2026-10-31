package io.terrakube.executor.service.status;

import io.terrakube.executor.service.logs.StepOutputBuffer;
import io.terrakube.executor.service.mode.TerraformJob;

public interface UpdateJobStatus {

    void setRunningStatus(TerraformJob job, String commitId);

    default void setCompletedStatus(boolean successful, boolean isPlan, int exitCode, TerraformJob job, StepOutputBuffer jobOutput, String jobErrorOutput, String jobPlan, String commitId) {
        setCompletedStatus(successful, isPlan, exitCode, job, jobOutput, jobErrorOutput, jobPlan, commitId, false, null);
    }

    default void setCompletedStatus(boolean successful, boolean isPlan, int exitCode, TerraformJob job, StepOutputBuffer jobOutput, String jobErrorOutput, String jobPlan, String commitId, boolean hasSoftMandatoryViolations, String approvalTeam) {
        setCompletedStatus(successful, isPlan, exitCode, job, jobOutput, jobErrorOutput, jobPlan, commitId, hasSoftMandatoryViolations, approvalTeam, null);
    }

    default void setCompletedStatus(boolean successful, boolean isPlan, int exitCode, TerraformJob job, StepOutputBuffer jobOutput, String jobErrorOutput, String jobPlan, String commitId, boolean hasSoftMandatoryViolations, String approvalTeam, String deliveryState) {
        setCompletedStatus(successful, isPlan, exitCode, job, jobOutput, jobErrorOutput, jobPlan, commitId, hasSoftMandatoryViolations, approvalTeam, deliveryState, null);
    }

    /**
     * Completes the step with its console output, which can be larger than memory. The caller closes the
     * buffer once this returns.
     */
    void setCompletedStatus(boolean successful, boolean isPlan, int exitCode, TerraformJob job, StepOutputBuffer jobOutput, String jobErrorOutput, String jobPlan, String commitId, boolean hasSoftMandatoryViolations, String approvalTeam, String deliveryState, Long deliveryRevision);
}
