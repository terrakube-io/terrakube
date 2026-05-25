package io.terrakube.api.plugin.storage.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadIntegrityTest {

    private static final String HELLO_SHA256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @Test
    void sha256HexMatchesKnownDigest() {
        assertEquals(HELLO_SHA256, UploadIntegrity.sha256Hex("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void verifySkippedWhenHeaderAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertNull(UploadIntegrity.verify(request, "hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void verifySkippedWhenHeaderBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UploadIntegrity.HEADER, "   ");
        assertNull(UploadIntegrity.verify(request, "hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void verifyPassesWhenDigestMatches() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UploadIntegrity.HEADER, HELLO_SHA256);
        assertNull(UploadIntegrity.verify(request, "hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void verifyAcceptsUppercaseHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UploadIntegrity.HEADER, HELLO_SHA256.toUpperCase());
        assertNull(UploadIntegrity.verify(request, "hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void verifyTrimsWhitespaceInHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UploadIntegrity.HEADER, "  " + HELLO_SHA256 + "  ");
        assertNull(UploadIntegrity.verify(request, "hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void verifyRejectsMismatchWith409AndJsonBody() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UploadIntegrity.HEADER, "deadbeef");

        ResponseEntity<String> response = UploadIntegrity.verify(request, "hello".getBytes(StandardCharsets.UTF_8));

        assertNotNull(response);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.contains("\"error\":\"sha256-mismatch\""));
        assertTrue(body.contains("\"expected\":\"deadbeef\""));
        assertTrue(body.contains("\"actual\":\"" + HELLO_SHA256 + "\""));
        assertTrue(body.contains("\"size\":5"));
    }
}
