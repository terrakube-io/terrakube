package io.terrakube.api.rs.hooks.announcement;

import com.yahoo.elide.core.exceptions.HttpStatusException;

public class AnnouncementManagementException extends HttpStatusException {
    public AnnouncementManagementException(int status, String message) {
        super(status, message);
    }
}

