package io.terrakube.registry.controller;

import io.terrakube.registry.plugin.storage.StorageUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Applies to both module endpoints: a cold getModuleVersionPath cache miss that hits a
// transient S3/API/VCS failure, and a module.zip request that fails to proxy bytes or generate a
// presigned URL. Either way the client gets a clearly logged, retryable response instead of an
// ambiguous success (e.g. an empty ZIP) or an opaque 500.
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
