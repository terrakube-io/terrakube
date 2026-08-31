package io.terrakube.api.plugin.security.federated;

import io.terrakube.api.plugin.security.request.RequestScopedMemo;
import io.terrakube.api.repository.FederatedRepository;
import io.terrakube.api.rs.federated.Federated;
import io.terrakube.api.rs.federated.claim.FederatedClaimMatcher;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the federated provider for a token issuer/audience pair, memoized for the life of the
 * current request.
 *
 * <p>Callers sit inside entity-level {@code @ReadPermission} checks, which Elide evaluates once per
 * record. Elide also materializes and permission-checks every member of a to-many relationship just
 * to emit its {@code {type, id}} linkage, so an unmemoized lookup turns a single response into
 * thousands of identical queries against a table that holds a handful of rows.
 *
 * <p>The memo is deliberately request-scoped rather than a TTL cache: {@link Federated} is mutable
 * at runtime through the API, and a cross-request cache would keep a revoked provider working until
 * expiry.
 */
@Service
@AllArgsConstructor
public class FederatedLookupService {

    private static final String CACHE_ATTRIBUTE = FederatedLookupService.class.getName();

    private final FederatedRepository federatedRepository;

    /**
     * Returns the matching provider row, if any. Claim matching is intentionally left to callers —
     * the verdict depends on per-user token claims, so caching it under an issuer/audience key would
     * let one user's result stand in for another's.
     */
    public Optional<Federated> findByIssuerUrlAndAudience(String issuerUrl, String audience) {
        return RequestScopedMemo.memoize(CACHE_ATTRIBUTE, Arrays.asList(issuerUrl, audience),
                () -> federatedRepository.findByIssuerUrlAndAudience(issuerUrl, audience));
    }

    /**
     * Resolves and authorizes a federated credential from decoded token claims.
     *
     * <p>Every audience is considered because OIDC tokens may contain more than one intended
     * recipient. Claim conditions remain part of this method so a caller cannot accidentally treat
     * an issuer/audience match as authorization.
     */
    public Optional<Federated> findAuthorized(Map<String, Object> tokenAttributes) {
        String issuer = FederatedTokenClaims.issuer(tokenAttributes);
        if (issuer.isEmpty()) {
            return Optional.empty();
        }
        return FederatedTokenClaims.audiences(tokenAttributes).stream()
                .map(audience -> findByIssuerUrlAndAudience(issuer, audience))
                .flatMap(Optional::stream)
                .filter(federated -> FederatedClaimMatcher.matchesClaims(federated, tokenAttributes))
                .findFirst();
    }
}
