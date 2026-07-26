package io.terrakube.api.rs.hooks.organization;

import com.yahoo.elide.core.exceptions.HttpStatusException;

public class OrganizationManagementException extends HttpStatusException {
    public OrganizationManagementException(int status, String message) {
        super(status, message);
    }
}
