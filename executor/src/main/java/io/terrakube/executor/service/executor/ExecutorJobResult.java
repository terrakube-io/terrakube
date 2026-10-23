package io.terrakube.executor.service.executor;

import io.terrakube.executor.service.logs.StepOutputBuffer;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExecutorJobResult {
    boolean successfulExecution;
    String planFile;
    String outputErrorLog;
    // Console output of the step. The buffer is closed by whoever saves it (ExecutorJobImpl).
    StepOutputBuffer outputBuffer;
    int exitCode;
    boolean isPlan;
    boolean hasSoftMandatoryViolations;
    String approvalTeam;
    String deliveryState;
    Long deliveryRevision;
    Long deliveryTimestamp;
}
