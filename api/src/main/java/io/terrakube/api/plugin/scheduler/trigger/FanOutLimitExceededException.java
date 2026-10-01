package io.terrakube.api.plugin.scheduler.trigger;

import com.yahoo.elide.core.exceptions.HttpStatusException;
import org.apache.hc.core5.http.HttpStatus;

/**
 * Raised when creating a run trigger would push a source workspace's enabled outbound edge
 * count past {@code io.terrakube.run-trigger.max-dependents-per-apply}. Answered as 400: the
 * request is well formed, there are just too many of them already.
 */
public class FanOutLimitExceededException extends HttpStatusException {

    public FanOutLimitExceededException(String message) {
        super(HttpStatus.SC_BAD_REQUEST, message);
    }
}
