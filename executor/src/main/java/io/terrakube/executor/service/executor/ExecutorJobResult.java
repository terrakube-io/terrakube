package io.terrakube.executor.service.executor;

import io.terrakube.executor.service.logs.StepOutputBuffer;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExecutorJobResult {
    boolean successfulExecution;
    String planFile;
    String outputLog;
    String outputErrorLog;
    // Console output too large to hold as a String. When set it is the output of the step, outputLog is empty.
    StepOutputBuffer outputBuffer;
    int exitCode;
    boolean isPlan;
    boolean hasSoftMandatoryViolations;
    String approvalTeam;
    String deliveryState;
    Long deliveryRevision;
    Long deliveryTimestamp;
}
