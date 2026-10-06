package io.terrakube.api.plugin.scheduler.trigger;

import com.yahoo.elide.core.exceptions.HttpStatusException;
import org.apache.hc.core5.http.HttpStatus;

/**
 * Raised when a run trigger's organization can't be locked for graph validation - deleted,
 * disabled, or otherwise unresolvable. Answered as 400: the edge is rejected, not let through.
 */
public class OrganizationUnresolvableException extends HttpStatusException {

    public OrganizationUnresolvableException(String message) {
        super(HttpStatus.SC_BAD_REQUEST, message);
    }
}
