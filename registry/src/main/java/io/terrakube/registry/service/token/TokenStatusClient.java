package io.terrakube.registry.service.token;

import feign.Param;
import feign.RequestLine;

/**
 * Lightweight Feign client for checking whether a {@code Terrakube} PAT or team token
 * ({@code jti}) is still active in the Terrakube API.
 */
public interface TokenStatusClient {

    @RequestLine("GET /internal/v1/tokens/{tokenId}/status")
    TokenStatusResponse getTokenStatus(@Param("tokenId") String tokenId);

    record TokenStatusResponse(boolean active) {
    }
}
