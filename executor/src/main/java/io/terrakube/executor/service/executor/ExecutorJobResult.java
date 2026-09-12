package io.terrakube.executor.service.executor;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExecutorJobResult {
    boolean successfulExecution;
    String planFile;
    String outputLog;
    String outputErrorLog;
    int exitCode;
    boolean isPlan;
    boolean hasSoftMandatoryViolations;
    String approvalTeam;
}
