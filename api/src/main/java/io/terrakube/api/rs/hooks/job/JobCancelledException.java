package io.terrakube.api.rs.hooks.job;

import com.yahoo.elide.core.exceptions.HttpStatusException;
import org.apache.hc.core5.http.HttpStatus;

/**
 * Raised when a request tries to move a cancelled job to another status. Answered as 409: a cancelled job stays
 * cancelled, including for an executor that was handed the job just before the cancel.
 */
public class JobCancelledException extends HttpStatusException {

    public JobCancelledException(String message) {
        super(HttpStatus.SC_CONFLICT, message);
    }
}
