package io.terrakube.api.plugin.error;

import com.yahoo.elide.ElideErrorResponse;
import com.yahoo.elide.ElideErrors;
import com.yahoo.elide.core.exceptions.TransactionException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataIntegrityExceptionMapperTest {

    private final DataIntegrityExceptionMapper mapper = new DataIntegrityExceptionMapper();

    @Test
    void uniqueViolationIsConflict() {
        ElideErrorResponse<ElideErrors> response = map(violation(ConstraintKind.UNIQUE, "23505"));

        assertEquals(409, response.getStatus());
        assertEquals("This operation conflicts with data that already exists.", message(response));
    }

    @Test
    void notNullViolationIsBadRequest() {
        ElideErrorResponse<ElideErrors> response = map(violation(ConstraintKind.NOT_NULL, "23502"));

        assertEquals(400, response.getStatus());
        assertEquals("A required value is missing or a value is not allowed.", message(response));
    }

    @Test
    void checkViolationIsBadRequest() {
        ElideErrorResponse<ElideErrors> response = map(violation(ConstraintKind.CHECK, "23514"));

        assertEquals(400, response.getStatus());
    }

    @Test
    void foreignKeyAndUnknownViolationsKeepConflict() {
        assertEquals(409, map(violation(ConstraintKind.FOREIGN_KEY, "23503")).getStatus());
        assertEquals(409, map(violation(ConstraintKind.OTHER, "23000")).getStatus());
        assertEquals(409, map(new TransactionException(new ConstraintViolationException(
                "legacy", new SQLException("legacy", "23000"), "constraint"))).getStatus());
    }

    @Test
    void otherTransactionFailureIsLocked() {
        ElideErrorResponse<ElideErrors> response = map(new TransactionException(new IllegalStateException("boom")));

        assertEquals(423, response.getStatus());
    }

    private ElideErrorResponse<ElideErrors> map(TransactionException exception) {
        return mapper.toErrorResponse(exception, null);
    }

    private static TransactionException violation(ConstraintKind kind, String sqlState) {
        return new TransactionException(new ConstraintViolationException(
                "constraint violated", new SQLException("constraint violated", sqlState), kind, "some_constraint"));
    }

    private static String message(ElideErrorResponse<ElideErrors> response) {
        return response.getBody().getErrors().get(0).getMessage();
    }
}
