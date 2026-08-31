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

    private Cache<String, Optional<FederatedConfig>> federatedCache;
    private Cache<String, ProviderManager> providerManagerCache;

    public Cache<String, Optional<FederatedConfig>> getFederatedCache() {
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
        String audience = extractClaimString(payloadMap, "aud");

        if (!tokenIssuer.isEmpty() && !audience.isEmpty()
                && !tokenIssuer.equals(jwtPat)
                && !tokenIssuer.equals(jwtInternal)
                && !tokenIssuer.equals(this.issuerUri)) {
            String cacheKey = tokenIssuer + ":" + audience;
            Optional<FederatedConfig> federatedOpt = getFederatedCache().get(cacheKey, key -> fetchFederatedConfig(tokenIssuer, audience));
            if (federatedOpt.isPresent()) {
                FederatedConfig config = federatedOpt.get();
                if (validateClaims(payloadMap, config.getClaims())) {
                    log.debug("Federated authentication matched for issuer: {}", config.getIssuerUrl());
                    return getProviderManagerCache().get(config.getIssuerUrl(), url ->
                            new ProviderManager(new JwtAuthenticationProvider(jwtDecoderFactory.apply(url)))
                    );
                }
            }
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

    private Optional<FederatedConfig> fetchFederatedConfig(String issuer, String audience) {
        if (terrakubeClient == null || issuer.isEmpty() || audience.isEmpty()) {
            return Optional.empty();
        }
        try {
            ResponseWithInclude<List<Federated>, FederatedClaim> response =
                    terrakubeClient.getFederatedByIssuerUrlAndAudienceWithClaims(issuer, audience);
            if (response != null && response.getData() != null && !response.getData().isEmpty()) {
                Federated federated = response.getData().get(0);
                FederatedConfig config = new FederatedConfig();
                config.setIssuerUrl(federated.getAttributes().getIssuerUrl());
                config.setAudience(federated.getAttributes().getAudience());
                Map<String, String> claimsMap = new HashMap<>();
                if (response.getIncluded() != null) {
                    for (FederatedClaim claim : response.getIncluded()) {
                        if (claim.getAttributes() != null && claim.getAttributes().getClaimKey() != null) {
                            claimsMap.put(claim.getAttributes().getClaimKey(), claim.getAttributes().getClaimValue());
                        }
                    }
                }
                config.setClaims(claimsMap);
                return Optional.of(config);
            }
        } catch (Exception ex) {
            log.error("Error fetching federated config from TerrakubeClient: {}", ex.getMessage());
        }
        return Optional.empty();
    }

    private boolean validateClaims(Map<String, Object> payloadMap, Map<String, String> requiredClaims) {
        if (requiredClaims == null || requiredClaims.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> entry : requiredClaims.entrySet()) {
            Object tokenVal = payloadMap.get(entry.getKey());
            if (tokenVal == null || !entry.getValue().equals(String.valueOf(tokenVal))) {
                log.debug("Federated claim mismatch for key {}: expected {}, got {}", entry.getKey(), entry.getValue(), tokenVal);
                return false;
            }
        }
        return true;
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

}
