package io.terrakube.api.plugin.storage.controller;

import io.terrakube.api.plugin.storage.StorageUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Applies across every endpoint backed by StorageTypeService (terraform state/plan, job
// context, the CLI-driven configuration tarball, policy evaluation, step output) - a genuine
// storage failure gets a clearly logged, retryable response instead of an ambiguous empty
// success or an opaque 500.
@Slf4j
@RestControllerAdvice
public class StorageExceptionHandler {

    @ExceptionHandler(StorageUnavailableException.class)
    public ResponseEntity<Void> handleStorageUnavailable(StorageUnavailableException exception) {
        log.error("Storage backend unavailable: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .build();
    }
}
