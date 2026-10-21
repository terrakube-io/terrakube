package io.terrakube.executor.service.logs;

import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Builder
@Slf4j
public class LogsConsumer implements Consumer<String> {

    @NonNull
    private Integer jobId;

    @NonNull
    private String stepId;

    @NonNull
    StepOutputBuffer terraformOutput;

    @NonNull
    ProcessLogs processLogs;

    @NonNull
    AtomicInteger lineNumber;

    @Override
    public void accept(String logs) {
        int line = lineNumber.addAndGet(1);
        if (processLogs.echoToServiceLog()) {
            log.info(logs);
        }
        terraformOutput.appendln(logs);
        processLogs.sendLogs(jobId, stepId, line, logs);
    }
}
