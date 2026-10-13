package io.terrakube.executor.service.logs;

public interface ProcessLogs {

    public void setupConsumerGroups(String jobId);

    public void sendLogs(Integer jobId, String stepId, int lineNumber, String output);

    public void sendStructuredUpdate(Integer jobId, String stepId, String structuredJson);

    // Blocks until every line already passed to sendLogs has been handed to its destination, so a
    // step can publish its completion without racing its own output. Best effort: a transport that
    // still can't deliver after its own retries keeps the lines for later and returns anyway.
    public void flush();
}
