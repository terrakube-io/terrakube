package io.terrakube.api.rs.hooks.globalvar;

import com.yahoo.elide.core.exceptions.HttpStatusException;

public class GlobalvarManagementException extends HttpStatusException {
    public GlobalvarManagementException(int status, String message) {
        super(status, message);
    }
}
