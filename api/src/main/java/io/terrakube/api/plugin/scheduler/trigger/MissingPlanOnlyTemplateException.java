package io.terrakube.api.plugin.scheduler.trigger;

import com.yahoo.elide.core.exceptions.HttpStatusException;
import org.apache.hc.core5.http.HttpStatus;

/**
 * Raised when a run trigger is saved with {@code onDestroy=PLAN_ONLY} but no
 * {@code onDestroyPlanTemplate}. Without one, {@link RunTriggerDispatchService} can only skip
 * the edge at dispatch time with a warning - rejecting it up front (including a direct
 * JSON:API write that bypasses the UI's own client-side requirement) means the policy can never
 * silently go inert. Answered as 400: the request is well formed, it just cannot function.
 */
public class MissingPlanOnlyTemplateException extends HttpStatusException {

    public MissingPlanOnlyTemplateException(String message) {
        super(HttpStatus.SC_BAD_REQUEST, message);
    }
}
