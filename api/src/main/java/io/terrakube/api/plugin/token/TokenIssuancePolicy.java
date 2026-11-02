package io.terrakube.api.plugin.token;

import io.terrakube.api.plugin.security.federated.FederatedLookupService;
import io.terrakube.api.plugin.security.federated.FederatedTokenClaims;
import io.terrakube.api.repository.PatRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side rules for minting personal access tokens and team tokens.
 */
@Slf4j
@Service
public class TokenIssuancePolicy {

    private final String dexIssuerUri;
    private final int maxDays;
    private final FederatedLookupService federatedLookupService;
    private final boolean allowPatIssuance;
    private final PatRepository patRepository;

    private static final String PAT_ISSUER = "Terrakube";

    public TokenIssuancePolicy(@Value("${io.terrakube.token.issuer-uri}") String dexIssuerUri,
                               @Value("${io.terrakube.token.max-days:0}") int maxDays,
                               FederatedLookupService federatedLookupService,
                               @Value("${io.terrakube.token.allow-pat-issuance:false}") boolean allowPatIssuance,
                               PatRepository patRepository) {
        this.dexIssuerUri = dexIssuerUri;
        this.maxDays = maxDays;
        this.federatedLookupService = federatedLookupService;
        this.allowPatIssuance = allowPatIssuance;
        this.patRepository = patRepository;
    }

    /**
     * Allows an interactive Dex user to mint tokens. When io.terrakube.token.allow-pat-issuance is
     * enabled, a personal access token may also mint tokens that expire no later than itself.
     */
    public void requireAllowedIssuer(JwtAuthenticationToken principalJwt, int days, int hours, int minutes) {
        if (allowPatIssuance && isPersonalAccessToken(principalJwt)) {
            requireLifetimeWithinPat(principalJwt, days, hours, minutes);
            return;
        }
        requireInteractiveUser(principalJwt);
    }

    /**
     * Only a user signed in through Dex may mint tokens. PATs, team tokens, internal tokens and
     * federated workload tokens are rejected so a token can never be used to mint a longer-lived one.
     */
    public void requireInteractiveUser(JwtAuthenticationToken principalJwt) {
        Map<String, Object> claims = principalJwt == null ? Map.of() : principalJwt.getTokenAttributes();
        String issuer = FederatedTokenClaims.issuer(claims);
        boolean dexUser = !issuer.isEmpty() && issuer.equals(dexIssuerUri)
                && federatedLookupService.findAllAuthorized(claims).isEmpty();
        if (!dexUser) {
            log.warn("Token creation denied for non-interactive principal with issuer {}", issuer);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an interactive user can create tokens");
        }
    }

    private boolean isPersonalAccessToken(JwtAuthenticationToken principalJwt) {
        if (principalJwt == null) {
            return false;
        }
        Map<String, Object> claims = principalJwt.getTokenAttributes();
        if (!PAT_ISSUER.equals(FederatedTokenClaims.issuer(claims)) || !federatedLookupService.findAllAuthorized(claims).isEmpty()) {
            return false;
        }
        // Team tokens share the issuer but are stored in a different table
        String jti = principalJwt.getToken().getId();
        if (jti == null) {
            return false;
        }
        UUID id;
        try {
            id = UUID.fromString(jti);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return patRepository.findById(id).map(pat -> !pat.isDeleted()).orElse(false);
    }

    private void requireLifetimeWithinPat(JwtAuthenticationToken principalJwt, int days, int hours, int minutes) {
        Instant patExpiry = principalJwt.getToken().getExpiresAt();
        if (patExpiry == null) {
            log.warn("Token creation denied for personal access token {} without expiry", principalJwt.getToken().getId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A personal access token without expiry cannot create tokens");
        }
        Duration lifetime = Duration.ofDays(days).plusHours(hours).plusMinutes(minutes);
        if (days < 0 || hours < 0 || minutes < 0 || lifetime.isZero() || Instant.now().plus(lifetime).isAfter(patExpiry)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A token created with a personal access token must expire no later than " + patExpiry);
        }
    }

    public void requireLifetimeWithinLimit(int days, int hours, int minutes) {
        if (days < 0 || hours < 0 || minutes < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token lifetime values cannot be negative");
        }
        if (maxDays <= 0) {
            return;
        }
        Duration lifetime = Duration.ofDays(days).plusHours(hours).plusMinutes(minutes);
        if (lifetime.isZero() || lifetime.compareTo(Duration.ofDays(maxDays)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    String.format("Token lifetime must be between 1 minute and %d days", maxDays));
        }
    }
}
