package io.terrakube.api.plugin.execution;

import com.yahoo.elide.core.exceptions.HttpStatusException;
import org.apache.hc.core5.http.HttpStatus;

/** Raised when a user who may plan tries to start a run that destroys. Answered as 403. */
public class JobRightForbiddenException extends HttpStatusException {

    public JobRightForbiddenException(String message) {
        super(HttpStatus.SC_FORBIDDEN, message);
    }
}
