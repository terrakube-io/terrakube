package io.terrakube.executor.service.status;

import io.terrakube.executor.service.mode.TerraformJob;

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
}
