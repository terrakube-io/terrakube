package io.terrakube.executor.service.status;

import io.terrakube.executor.service.logs.StepOutputBuffer;
import io.terrakube.executor.service.mode.TerraformJob;

public interface UpdateJobStatus {

    void setRunningStatus(TerraformJob job, String commitId);

    /**
     * Completes the step with its console output, which can be larger than memory. The caller closes the
     * buffer once this returns.
     */
    void setCompletedStatus(boolean successful, boolean isPlan, int exitCode, TerraformJob job, StepOutputBuffer jobOutput, String jobErrorOutput, String jobPlan, String commitId, boolean hasSoftMandatoryViolations, String approvalTeam, String deliveryState, Long deliveryRevision);
}
