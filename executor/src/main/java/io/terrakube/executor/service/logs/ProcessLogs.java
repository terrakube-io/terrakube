package io.terrakube.executor.service.logs;

public interface ProcessLogs {

    public void setupConsumerGroups(String jobId);

    public void sendLogs(Integer jobId, String stepId, int lineNumber, String output);
}
