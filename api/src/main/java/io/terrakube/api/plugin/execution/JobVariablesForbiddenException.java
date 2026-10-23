package io.terrakube.api.plugin.execution;

import com.yahoo.elide.core.exceptions.HttpStatusException;
import org.apache.hc.core5.http.HttpStatus;

/** Raised when a user who cannot manage the workspace sends job variables. Answered as 403. */
public class JobVariablesForbiddenException extends HttpStatusException {

    public JobVariablesForbiddenException(String message) {
        super(HttpStatus.SC_FORBIDDEN, message);
    }
}
