package io.terrakube.api.plugin.scheduler.trigger;

import com.yahoo.elide.core.exceptions.HttpStatusException;
import org.apache.hc.core5.http.HttpStatus;

/**
 * Raised when a run trigger would close a loop in the organization's dependency graph.
 * Answered as 400: the request is well formed, the resulting graph is not.
 */
public class CyclicDependencyException extends HttpStatusException {

    public CyclicDependencyException(String message) {
        super(HttpStatus.SC_BAD_REQUEST, message);
    }
}
