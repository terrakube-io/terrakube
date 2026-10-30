package io.terrakube.api.plugin.execution;

import com.yahoo.elide.core.exceptions.HttpStatusException;
import org.apache.hc.core5.http.HttpStatus;

/** Raised when a job would change infrastructure from a branch the workspace does not allow. Answered as 403. */
public class JobBranchPolicyException extends HttpStatusException {

    public JobBranchPolicyException(String message) {
        super(HttpStatus.SC_FORBIDDEN, message);
    }
}
