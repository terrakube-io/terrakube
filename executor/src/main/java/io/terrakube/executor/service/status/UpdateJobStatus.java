package io.terrakube.executor.service.status;

import io.terrakube.executor.service.mode.TerraformJob;

import io.terrakube.executor.service.logs.StepOutputBuffer;

public interface UpdateJobStatus {

    void setRunningStatus(TerraformJob job, String commitId);

    default void setCompletedStatus(boolean successful, boolean isPlan, int exitCode, TerraformJob job, String jobOutput, String jobErrorOutput, String jobPlan, String commitId) {
        setCompletedStatus(successful, isPlan, exitCode, job, jobOutput, jobErrorOutput, jobPlan, commitId, false, null);
    }

    default void setCompletedStatus(boolean successful, boolean isPlan, int exitCode, TerraformJob job, String jobOutput, String jobErrorOutput, String jobPlan, String commitId, boolean hasSoftMandatoryViolations, String approvalTeam) {
        setCompletedStatus(successful, isPlan, exitCode, job, jobOutput, jobErrorOutput, jobPlan, commitId, hasSoftMandatoryViolations, approvalTeam, null);
    }

    default void setCompletedStatus(boolean successful, boolean isPlan, int exitCode, TerraformJob job, String jobOutput, String jobErrorOutput, String jobPlan, String commitId, boolean hasSoftMandatoryViolations, String approvalTeam, String deliveryState) {
        setCompletedStatus(successful, isPlan, exitCode, job, jobOutput, jobErrorOutput, jobPlan, commitId, hasSoftMandatoryViolations, approvalTeam, deliveryState, null);
    }

    void setCompletedStatus(boolean successful, boolean isPlan, int exitCode, TerraformJob job, String jobOutput, String jobErrorOutput, String jobPlan, String commitId, boolean hasSoftMandatoryViolations, String approvalTeam, String deliveryState, Long deliveryRevision);

    /**
     * Same as the full {@code setCompletedStatus}, for a step whose console output is held in a buffer
     * that can be larger than memory. Implementations that can stream it should; this fallback loads it.
     */
    default void setCompletedStatusWithOutput(boolean successful, boolean isPlan, int exitCode, TerraformJob job, StepOutputBuffer jobOutput, String jobErrorOutput, String jobPlan, String commitId, boolean hasSoftMandatoryViolations, String approvalTeam, String deliveryState, Long deliveryRevision) {
        setCompletedStatus(successful, isPlan, exitCode, job, jobOutput.toString(), jobErrorOutput, jobPlan, commitId, hasSoftMandatoryViolations, approvalTeam, deliveryState, deliveryRevision);
    }
}
