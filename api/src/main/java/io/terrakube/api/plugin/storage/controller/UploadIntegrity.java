package io.terrakube.api.plugin.storage.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Header-driven integrity check. The executor sends {@code X-Content-Sha256}
 * with the hex sha-256 of the body it intends to deliver. The receiving
 * controller buffers the body, recomputes the digest, and only forwards the
 * bytes to the storage backend when the digest matches.
 *
 * If the header is absent the check is skipped — preserves backward
 * compatibility with the existing archive PUTs that have no integrity story.
 */
@Slf4j
final class UploadIntegrity {

    static final String HEADER = "X-Content-Sha256";

    private UploadIntegrity() {}

    /**
     * @return a populated ResponseEntity describing the mismatch when the
     *         expected and actual digests diverge; {@code null} when the
     *         payload is safe to commit (either header was absent or the
     *         digest matched).
     */
    static ResponseEntity<String> verify(HttpServletRequest request, byte[] body) {
        String expected = request.getHeader(HEADER);
        if (expected == null || expected.isBlank()) {
            return null;
        }
        String actual = sha256Hex(body);
        if (actual.equalsIgnoreCase(expected.trim())) {
            return null;
        }
        log.warn("Upload rejected: integrity check failed (expected={}, actual={}, bytes={})", expected, actual, body.length);
        String json = String.format("{\"error\":\"sha256-mismatch\",\"expected\":\"%s\",\"actual\":\"%s\",\"size\":%d}",
                expected, actual, body.length);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
