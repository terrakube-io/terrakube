package io.terrakube.api.plugin.security.authentication.dex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.io.Decoders;
import io.terrakube.api.plugin.security.federated.FederatedTokenClaims;
import io.terrakube.api.repository.FederatedRepository;
import io.terrakube.api.repository.PatRepository;
import io.terrakube.api.repository.TeamTokenRepository;
import io.terrakube.api.rs.federated.Federated;
import io.terrakube.api.rs.federated.claim.FederatedClaimMatcher;
import io.terrakube.api.rs.token.group.Group;
import io.terrakube.api.rs.token.pat.Pat;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Builder
@Getter
@Setter
@Slf4j
public class DexAuthenticationManagerResolver implements AuthenticationManagerResolver<HttpServletRequest> {

    private static final String jwtTypePat = "Terrakube";
    private static final String jwtTypeInternal = "TerrakubeInternal";
    private String dexIssuerUri;
    private String patJwtSecret;
    private String internalJwtSecret;
    private PatRepository patRepository;
    private TeamTokenRepository teamTokenRepository;
    private FederatedRepository federatedRepository;

    // Avoids re-fetching issuer metadata from Dex on every request.
    @Builder.Default
    private final Map<String, JwtDecoder> decoderCache = new ConcurrentHashMap<>();

    @Override
    public AuthenticationManager resolve(HttpServletRequest request) {
        Map<String, Object> tokenAttributes = getJwtClaims(request);
        String issuer = FederatedTokenClaims.issuer(tokenAttributes);
        List<String> audiences = FederatedTokenClaims.audiences(tokenAttributes);
        log.debug("Issuer: {} Audiences: {}", issuer, audiences);

        // Only Terrakube-issued PAT and team tokens are stored in the revocation tables. External
        // OIDC providers are free to use a non-UUID jti and must never be rejected for doing so.
        if (jwtTypePat.equals(issuer) && isTokenDeleted(claimAsString(tokenAttributes, "jti"))) {
            // Force a revoked token to use the wrong key so authentication always fails.
            issuer = jwtTypeInternal;
        }

        boolean federatedCredentialFound = false;
        for (String audience : audiences) {
            Optional<Federated> federated = federatedRepository.findByIssuerUrlAndAudience(issuer, audience);
            if (federated.isEmpty()) {
                continue;
            }
            federatedCredentialFound = true;
            if (FederatedClaimMatcher.matchesClaims(federated.get(), tokenAttributes)) {
                log.debug("Federated issuer found: {}", federated.get().getIssuerUrl());
                return new ProviderManager(new JwtAuthenticationProvider(getIssuerDecoder(federated.get().getIssuerUrl())));
            }
        }

        if (federatedCredentialFound) {
            // A trusted issuer/audience pair with failed claim conditions is an explicit denial. Do
            // not silently retry it against Dex or expose which condition did not match.
            throw new BadCredentialsException("Federated token is not authorized");
        }

        switch (issuer) {
            case jwtTypePat:
                log.debug("Using Terrakube Authentication Provider");
                return new ProviderManager(new JwtAuthenticationProvider(getJwtEncoder(jwtTypePat)));
            case jwtTypeInternal:
                log.debug("Using Terrakube Internal Authentication Provider");
                return new ProviderManager(new JwtAuthenticationProvider(getJwtEncoder(jwtTypeInternal)));
            default:
                log.debug("Using Dex JWT Authentication Provider");
                return new ProviderManager(new JwtAuthenticationProvider(getIssuerDecoder(this.dexIssuerUri)));
        }
    }

    // computeIfAbsent drops failed mappings, so a failed fetch is retried next request.
    private JwtDecoder getIssuerDecoder(String issuerUri) {
        return decoderCache.computeIfAbsent(issuerUri, uri -> {
            try {
                return JwtDecoders.fromIssuerLocation(uri);
            } catch (RuntimeException ex) {
                log.warn("Unable to load JWT decoder metadata from issuer {}: {}", uri, ex.getMessage());
                // AuthenticationException -> clean 401 instead of an unhandled 500.
                throw new AuthenticationServiceException("Unable to reach identity provider", ex);
            }
        });
    }

    private JwtDecoder getJwtEncoder(String issuerType) {
        String cacheKey = "secret:" + issuerType;
        JwtDecoder cached = decoderCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String jwtSecret = (issuerType.equals(jwtTypePat) ? patJwtSecret : internalJwtSecret);
        SecretKey jwtSecretKey = new SecretKeySpec(Decoders.BASE64URL.decode(jwtSecret), "HMACSHA256");
        JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoderCache.putIfAbsent(cacheKey, decoder);
        return decoderCache.get(cacheKey);
    }

    private Map<String, Object> getJwtClaims(HttpServletRequest request) {
        String authorization = request.getHeader("authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Collections.emptyMap();
        }
        String[] chunksToken = authorization.substring("Bearer ".length()).split("\\.");
        if (chunksToken.length != 3) {
            return Collections.emptyMap();
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(chunksToken[1]), StandardCharsets.UTF_8);
            return new ObjectMapper().readValue(payload, HashMap.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            log.debug("Unable to parse JWT claims: {}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    private String claimAsString(Map<String, Object> tokenAttributes, String claim) {
        Object value = tokenAttributes.get(claim);
        return value instanceof String ? (String) value : "";
    }

    private boolean isTokenDeleted(String tokenId) {
        if (tokenId != null && !tokenId.isEmpty()) {
            UUID id;
            try {
                id = UUID.fromString(tokenId);
            } catch (IllegalArgumentException ex) {
                return false;
            }
            Optional<Pat> searchPat = patRepository.findById(id);
            Optional<Group> searchGroupToken = teamTokenRepository.findById(id);
            if (searchPat.isPresent()) {
                Pat pat = searchPat.get();
                if (pat.isDeleted()) {
                    return true;
                } else return false;
            }

            if (searchGroupToken.isPresent()) {
                Group group = searchGroupToken.get();
                if (group.isDeleted()) {
                    return true;
                } else return false;
            }
        }

        return false;
    }
}
