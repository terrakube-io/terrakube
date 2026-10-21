package io.terrakube.api.plugin.policy;

import com.yahoo.elide.core.exceptions.HttpStatusException;
import org.apache.hc.core5.http.HttpStatus;

/**
 * Raised when a job is approved while its policy evaluations still block it. Answered as 409: the request is
 * allowed for this user, the job is just not in a state that can be approved.
 */
public class PolicyBlocksApprovalException extends HttpStatusException {

    public PolicyBlocksApprovalException(String message) {
        super(HttpStatus.SC_CONFLICT, message);
    }
}
