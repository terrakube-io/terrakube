package io.terrakube.api.plugin.token;

import io.terrakube.api.plugin.security.token.InternalTokenClassifier;
import io.terrakube.api.plugin.security.token.InternalTokenType;
import io.terrakube.api.repository.PatRepository;
import io.terrakube.api.repository.TeamTokenRepository;
import io.terrakube.api.rs.token.group.Group;
import io.terrakube.api.rs.token.pat.Pat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * Internal endpoint used by the Terrakube Registry service to verify whether a
 * {@code Terrakube} PAT or team token ({@code jti}) is still active.
 */
@RestController
@RequestMapping("/internal/v1/tokens")
public class InternalTokenStatusController {

    private final PatRepository patRepository;
    private final TeamTokenRepository teamTokenRepository;

    public InternalTokenStatusController(
            PatRepository patRepository,
            TeamTokenRepository teamTokenRepository) {
        this.patRepository = patRepository;
        this.teamTokenRepository = teamTokenRepository;
    }

    @GetMapping(value = "/{tokenId}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TokenStatusResponse> getTokenStatus(
            @PathVariable("tokenId") String tokenId,
            Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)
                || InternalTokenClassifier.classify(jwt.getTokenAttributes()) != InternalTokenType.REGISTRY_SERVICE) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new TokenStatusResponse(isTokenActive(tokenId)));
    }

    private boolean isTokenActive(String tokenId) {
        UUID id;
        try {
            id = UUID.fromString(tokenId);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        Optional<Pat> searchPat = patRepository.findById(id);
        if (searchPat.isPresent()) {
            return !searchPat.get().isDeleted();
        }
        Optional<Group> searchGroupToken = teamTokenRepository.findById(id);
        return searchGroupToken.isPresent() && !searchGroupToken.get().isDeleted();
    }

    public record TokenStatusResponse(boolean active) {
    }
}
