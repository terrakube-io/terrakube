package io.terrakube.api.plugin.security.federated;

import io.terrakube.api.repository.FederatedRepository;
import io.terrakube.api.rs.federated.Federated;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Objects;
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
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return federatedRepository.findByIssuerUrlAndAudience(issuerUrl, audience);
        }

        String slot = CACHE_ATTRIBUTE + '#' + issuerUrl + '#' + audience;
        Memo memo = (Memo) attributes.getAttribute(slot, RequestAttributes.SCOPE_REQUEST);
        if (memo != null && memo.answers(issuerUrl, audience)) {
            return memo.result();
        }

        Optional<Federated> result = federatedRepository.findByIssuerUrlAndAudience(issuerUrl, audience);
        attributes.setAttribute(slot, new Memo(issuerUrl, audience, result), RequestAttributes.SCOPE_REQUEST);
        return result;
    }

    /**
     * One immutable entry per attribute slot rather than a lazily created container.
     * {@link RequestAttributes} exposes no atomic get-or-create, so a shared container would have to
     * be published with a get-then-set that can drop an entry under concurrency. Independent slots
     * cannot: a racing writer only rewrites its own key with an equal value.
     *
     * <p>The pair is stored next to the result and re-checked on read, so two pairs that flatten to
     * the same slot name re-query instead of borrowing each other's answer.
     */
    private record Memo(String issuerUrl, String audience, Optional<Federated> result) {

        boolean answers(String issuerUrl, String audience) {
            return Objects.equals(this.issuerUrl, issuerUrl) && Objects.equals(this.audience, audience);
        }
    }
}
