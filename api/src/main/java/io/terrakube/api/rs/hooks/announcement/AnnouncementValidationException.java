package io.terrakube.api.rs.hooks.announcement;

import com.yahoo.elide.core.exceptions.HttpStatusException;

public class AnnouncementValidationException extends HttpStatusException {
    public AnnouncementValidationException(int status, String message) {
        super(status, message);
    }
}
