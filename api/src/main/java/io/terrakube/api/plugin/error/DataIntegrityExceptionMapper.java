package io.terrakube.api.plugin.error;

import com.yahoo.elide.ElideErrorResponse;
import com.yahoo.elide.ElideErrors;
import com.yahoo.elide.core.exceptions.ErrorContext;
import com.yahoo.elide.core.exceptions.ExceptionMapper;
import com.yahoo.elide.core.exceptions.TransactionException;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Component;

/**
 * Safety net for unique-constraint violations that reach the database despite an entity hook's
 * proactive check (e.g. {@code ModuleManageHook.validateNoDuplicateModule}) - such checks are
 * inherently racy (check-then-insert), so a concurrent request, or a background job writing to the
 * same row, can still hit the constraint after the check passed.
 *
 * <p>Elide wraps any failure during its own transaction flush - including a Hibernate
 * {@link ConstraintViolationException} - in its own {@link TransactionException}, which is what
 * actually reaches Elide's exception-mapper lookup (registered here via the {@link ExceptionMapper}
 * SPI - a plain Spring bean of this type is auto-collected by
 * {@code ElideAutoConfiguration.exceptionMappersBuilder}). {@code TransactionException} already
 * extends Elide's {@code HttpStatusException} with a default status of 423, which is what a client
 * saw - including the raw SQL statement in the error detail - before this mapper existed.
 */
@Slf4j
@Component
public class DataIntegrityExceptionMapper implements ExceptionMapper<TransactionException, ElideErrors> {

    @Override
    public ElideErrorResponse<ElideErrors> toErrorResponse(TransactionException exception, ErrorContext errorContext) {
        if (exception.getCause() instanceof ConstraintViolationException) {
            log.warn("Rejecting request due to a data integrity violation: {}", exception.getCause().getMessage());
            return ElideErrorResponse.status(409)
                    .errors(errors -> errors.error(error -> error.message(
                            "This operation conflicts with data that already exists.")));
        }
        log.error("Unhandled transaction exception: {}", exception.getMessage());
        return ElideErrorResponse.status(423)
                .errors(errors -> errors.error(error -> error.message("The request could not be completed.")));
    }
}
