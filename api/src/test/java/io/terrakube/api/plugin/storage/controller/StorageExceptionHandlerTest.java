package io.terrakube.api.plugin.storage.controller;

import io.terrakube.api.plugin.storage.StorageUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorageExceptionHandlerTest {

    @Test
    void mapsStorageUnavailableToServiceUnavailableWithRetryAfter() {
        StorageExceptionHandler handler = new StorageExceptionHandler();
        StorageUnavailableException exception = new StorageUnavailableException("boom", new RuntimeException("cause"));

        ResponseEntity<Void> response = handler.handleStorageUnavailable(exception);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("5", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }
}
