package io.terrakube.registry.configuration.authentication.dex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.federated.Federated;
import io.terrakube.client.model.federated.claim.FederatedClaim;
import io.terrakube.client.model.response.ResponseWithInclude;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

import javax.crypto.SecretKey;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Builder
@Getter
@Setter
@Slf4j
public class RegistryAuthenticationManagerResolver implements AuthenticationManagerResolver<HttpServletRequest> {

    private static final String jwtPat = "Terrakube";
    private static final String jwtInternal = "TerrakubeInternal";
    private String internalSecret;
    private String issuerUri;
    private String patSecret;
    private TerrakubeClient terrakubeClient;

    @Builder.Default
    private long federatedCacheExpireAfterWrite = 10;

    @Builder.Default
    private long federatedCacheMaximumSize = 1000;

    @Builder.Default
    private long providerManagerCacheExpireAfterWrite = 60;

    @Builder.Default
    private long providerManagerCacheMaximumSize = 100;

    @Builder.Default
    private java.util.function.Function<String, JwtDecoder> jwtDecoderFactory = JwtDecoders::fromIssuerLocation;

    private Cache<FederatedCacheKey, List<FederatedConfig>> federatedCache;
    private Cache<String, ProviderManager> providerManagerCache;

    public Cache<FederatedCacheKey, List<FederatedConfig>> getFederatedCache() {
        if (federatedCache == null) {
            federatedCache = Caffeine.newBuilder()
                    .expireAfterWrite(federatedCacheExpireAfterWrite, TimeUnit.MINUTES)
                    .maximumSize(federatedCacheMaximumSize)
                    .build();
        }
        return federatedCache;
    }

    public Cache<String, ProviderManager> getProviderManagerCache() {
        if (providerManagerCache == null) {
            providerManagerCache = Caffeine.newBuilder()
                    .expireAfterWrite(providerManagerCacheExpireAfterWrite, TimeUnit.MINUTES)
                    .maximumSize(providerManagerCacheMaximumSize)
                    .build();
        }
        return providerManagerCache;
    }

    @Override
    public AuthenticationManager resolve(HttpServletRequest request) {
        ProviderManager providerManager = null;
        Map<String, Object> payloadMap = getJwtPayload(request);
        String tokenIssuer = extractClaimString(payloadMap, "iss");
        List<String> audiences = extractClaimStrings(payloadMap, "aud");

        boolean federatedCredentialFound = false;
        if (!tokenIssuer.isEmpty() && !audiences.isEmpty()
                && !tokenIssuer.equals(jwtPat)
                && !tokenIssuer.equals(jwtInternal)
                && !tokenIssuer.equals(this.issuerUri)) {
            for (String audience : audiences) {
                FederatedCacheKey cacheKey = new FederatedCacheKey(tokenIssuer, audience);
                List<FederatedConfig> federatedConfigs = getFederatedCache().get(
                        cacheKey, key -> fetchFederatedConfigs(key.issuer(), key.audience()));
                federatedCredentialFound |= !federatedConfigs.isEmpty();
                for (FederatedConfig config : federatedConfigs) {
                    if (validateClaims(payloadMap, config.getClaims())) {
                        log.debug("Federated authentication matched for issuer: {}", config.getIssuerUrl());
                        return getProviderManagerCache().get(config.getIssuerUrl(), url ->
                                new ProviderManager(new JwtAuthenticationProvider(jwtDecoderFactory.apply(url)))
                        );
                    }
                }
            }
        }

        if (federatedCredentialFound) {
            throw new BadCredentialsException("Federated token is not authorized");
        }

        switch (tokenIssuer) {
            case jwtInternal:
                // Cache key is stable: same secret → same decoder for the process lifetime.
                providerManager = getProviderManagerCache().get("internal",
                        k -> new ProviderManager(new JwtAuthenticationProvider(getJwtEncoder(jwtInternal))));
                break;
            case jwtPat:
                providerManager = getProviderManagerCache().get("pat",
                        k -> new ProviderManager(new JwtAuthenticationProvider(getJwtEncoder(jwtPat))));
                break;
            default:
                providerManager = getProviderManagerCache().get(this.issuerUri,
                        k -> new ProviderManager(new JwtAuthenticationProvider(jwtDecoderFactory.apply(this.issuerUri))));
                break;
        }
        return providerManager;
    }

    private List<FederatedConfig> fetchFederatedConfigs(String issuer, String audience) {
        if (terrakubeClient == null || issuer.isEmpty() || audience.isEmpty()) {
            return List.of();
        }
        try {
            ResponseWithInclude<List<Federated>, FederatedClaim> response =
                    terrakubeClient.getFederatedByIssuerUrlAndAudienceWithClaims(issuer, audience);
            if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                Map<String, FederatedClaim> includedClaims = new HashMap<>();
                if (response.getIncluded() != null) {
                    response.getIncluded().stream()
                            .filter(Objects::nonNull)
                            .filter(claim -> claim.getId() != null)
                            .forEach(claim -> includedClaims.put(claim.getId(), claim));
                }
                return response.getData().stream()
                        .filter(Objects::nonNull)
                        .map(federated -> toFederatedConfig(federated, includedClaims))
                        .toList();
            }
        } catch (Exception ex) {
            log.error("Error fetching federated config from TerrakubeClient: {}", ex.getMessage());
        }
        return List.of();
    }

    private FederatedConfig toFederatedConfig(Federated federated, Map<String, FederatedClaim> includedClaims) {
        FederatedConfig config = new FederatedConfig();
        if (federated.getAttributes() != null) {
            config.setIssuerUrl(federated.getAttributes().getIssuerUrl());
            config.setAudience(federated.getAttributes().getAudience());
        }
        Map<String, String> claimsMap = new LinkedHashMap<>();
        if (federated.getRelationships() != null
                && federated.getRelationships().getClaims() != null
                && federated.getRelationships().getClaims().getData() != null) {
            federated.getRelationships().getClaims().getData().stream()
                    .map(claimResource -> includedClaims.get(claimResource.getId()))
                    .filter(Objects::nonNull)
                    .filter(claim -> claim.getAttributes() != null)
                    .filter(claim -> claim.getAttributes().getClaimKey() != null)
                    .forEach(claim -> claimsMap.put(
                            claim.getAttributes().getClaimKey(), claim.getAttributes().getClaimValue()));
        }
        config.setClaims(claimsMap);
        return config;
    }

    private boolean validateClaims(Map<String, Object> payloadMap, Map<String, String> requiredClaims) {
        if (requiredClaims == null || requiredClaims.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> entry : requiredClaims.entrySet()) {
            Object tokenVal = payloadMap.get(entry.getKey());
            if (tokenVal == null || !claimMatches(tokenVal, entry.getValue())) {
                log.debug("Federated claim mismatch for key {}: expected {}, got {}", entry.getKey(), entry.getValue(), tokenVal);
                return false;
            }
        }
        return true;
    }

    private boolean claimMatches(Object tokenValue, String expected) {
        if (tokenValue instanceof Collection<?> values) {
            return values.stream().filter(Objects::nonNull).anyMatch(value -> expected.equals(value.toString()));
        }
        return expected.equals(tokenValue.toString());
    }

    private Map<String, Object> getJwtPayload(HttpServletRequest request) {
        if (request == null || request.getHeader("authorization") == null) {
            return Collections.emptyMap();
        }
        String authHeader = request.getHeader("authorization");
        if (!authHeader.startsWith("Bearer ")) {
            return Collections.emptyMap();
        }
        String token = authHeader.replace("Bearer ", "");
        String[] chunks = token.split("\\.");
        if (chunks.length < 2) {
            return Collections.emptyMap();
        }
        Base64.Decoder decoder = Base64.getUrlDecoder();
        try {
            String payload = new String(decoder.decode(chunks[1]));
            return new ObjectMapper().readValue(payload, HashMap.class);
        } catch (Exception e) {
            log.error("Error parsing JWT payload: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private String extractClaimString(Map<String, Object> payloadMap, String claim) {
        if (payloadMap == null || !payloadMap.containsKey(claim)) {
            return "";
        }
        Object val = payloadMap.get(claim);
        if (val instanceof String) {
            return (String) val;
        } else if (val instanceof List) {
            List<?> list = (List<?>) val;
            if (!list.isEmpty()) {
                return String.valueOf(list.get(0));
            }
        }
        return "";
    }

    private List<String> extractClaimStrings(Map<String, Object> payloadMap, String claim) {
        if (payloadMap == null || !payloadMap.containsKey(claim)) {
            return List.of();
        }
        Object value = payloadMap.get(claim);
        if (value instanceof String stringValue) {
            return stringValue.isEmpty() ? List.of() : List.of(stringValue);
        }
        if (value instanceof Collection<?> values) {
            return values.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(item -> !item.isEmpty())
                    .distinct()
                    .toList();
        }
        return List.of();
    }

    private JwtDecoder getJwtEncoder(String issuerType) {
        String tokenSecret = (issuerType.equals(jwtPat) ? patSecret : internalSecret);
        byte[] secretBytes = Decoders.BASE64URL.decode(tokenSecret);
        SecretKey jwtTokenKey = Keys.hmacShaKeyFor(secretBytes);
        MacAlgorithm macAlgorithm = getMacAlgorithm(secretBytes.length);
        return NimbusJwtDecoder.withSecretKey(jwtTokenKey).macAlgorithm(macAlgorithm).build();
    }

    private MacAlgorithm getMacAlgorithm(int keyLengthBytes) {
        if (keyLengthBytes >= 64) {
            return MacAlgorithm.HS512;
        } else if (keyLengthBytes >= 48) {
            return MacAlgorithm.HS384;
        } else {
            return MacAlgorithm.HS256;
        }
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class FederatedConfig {
        private String issuerUrl;
        private String audience;
        private Map<String, String> claims;
    }

    public record FederatedCacheKey(String issuer, String audience) {}

}
