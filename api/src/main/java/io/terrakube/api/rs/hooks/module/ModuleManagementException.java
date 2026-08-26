package io.terrakube.api.rs.hooks.module;

import com.yahoo.elide.core.exceptions.HttpStatusException;

public class ModuleManagementException extends HttpStatusException {
    public ModuleManagementException(int status, String message) {
        super(status, message);
    }
}
