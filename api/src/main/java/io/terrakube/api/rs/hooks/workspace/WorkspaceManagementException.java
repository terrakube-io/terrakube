package io.terrakube.api.rs.hooks.workspace;

import com.yahoo.elide.core.exceptions.HttpStatusException;

public class WorkspaceManagementException extends HttpStatusException {
    public WorkspaceManagementException(int status, String message) {
        super(status, message);
    }
}
