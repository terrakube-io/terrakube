package io.terrakube.api.plugin.scheduler.trigger;

/**
 * At least one dependent in a fan-out failed to enqueue. Thrown by
 * {@link RunTriggerDispatchService#dispatchInternal} so {@link RunTriggerEventDispatchService}
 * sees a failure and retries the whole job's fan-out with backoff, instead of the loop
 * swallowing the error and the outbox event being recorded as {@code PROCESSED} for good.
 */
class RunTriggerDispatchPartialFailureException extends RuntimeException {

    RunTriggerDispatchPartialFailureException(String message) {
        super(message);
    }
}
