package io.terrakube.api.rs.hooks.project;

import com.yahoo.elide.core.exceptions.HttpStatusException;
import org.apache.hc.core5.http.HttpStatus;

/**
 * Raised when a project that still has workspaces is deleted. Answered as 409 with a message naming how many
 * workspaces block the delete, instead of the foreign key violation the database would otherwise surface.
 */
public class ProjectDeleteGuardException extends HttpStatusException {

    public ProjectDeleteGuardException(String message) {
        super(HttpStatus.SC_CONFLICT, message);
    }
}
