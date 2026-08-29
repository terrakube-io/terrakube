package io.terrakube.api.plugin.streaming;

/** Thrown when a pod already holds its configured maximum of concurrent step-log SSE connections. */
public class SseCapacityExceededException extends RuntimeException {

    public SseCapacityExceededException(int limit) {
        super("Step-log SSE connection limit reached (" + limit + ")");
    }
}
