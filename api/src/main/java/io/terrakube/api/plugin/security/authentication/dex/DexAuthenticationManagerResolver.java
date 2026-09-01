package io.terrakube.api.plugin.security.authentication.dex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.terrakube.api.repository.FederatedRepository;
import io.terrakube.api.repository.PatRepository;
import io.terrakube.api.repository.TeamTokenRepository;
import io.terrakube.api.rs.federated.Federated;
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
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

import javax.crypto.SecretKey;
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
        ProviderManager providerManager = null;
        String issuer = "";
        String audience = "";
        String federatedIssuer = "";
        try {
            issuer = getJwtClaim(request, "iss");
            audience = getJwtClaim(request, "aud");
            log.debug("Issuer: {} Audience: {}", issuer, audience);
            if (isTokenDeleted(getJwtClaim(request, "jti"))) {
                //FORCE TOKEN TO USE INTERNAL AUTH SO IT CAN ALWAYS FAIL
                issuer = jwtTypeInternal;
            }
            Federated federated = federatedRepository.findByIssuerUrlAndAudience(issuer, audience).orElse(null);
            if (federated != null) {
                log.debug("Federated issuer found: {}", federated.getIssuerUrl());
                federatedIssuer = federated.getIssuerUrl();
            }
        } catch (Exception ex) {
            log.info(ex.getMessage());
        }

        if (!federatedIssuer.isEmpty()) {
            providerManager = new ProviderManager(new JwtAuthenticationProvider(getIssuerDecoder(federatedIssuer)));
        } else {
            switch (issuer) {
                case jwtTypePat:
                    log.debug("Using Terrakube Authentication Provider");
                    providerManager = new ProviderManager(new JwtAuthenticationProvider(getJwtEncoder(jwtTypePat)));
                    break;
                case jwtTypeInternal:
                    log.debug("Using Terrakube Internal Authentication Provider");
                    providerManager = new ProviderManager(new JwtAuthenticationProvider(getJwtEncoder(jwtTypeInternal)));
                    break;
                default:
                    log.debug("Using Dex JWT Authentication Provider");
                    providerManager = new ProviderManager(new JwtAuthenticationProvider(getIssuerDecoder(this.dexIssuerUri)));
                    break;
            }
        }
        return providerManager;
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
        byte[] secretBytes = Decoders.BASE64URL.decode(jwtSecret);
        SecretKey jwtSecretKey = Keys.hmacShaKeyFor(secretBytes);
        MacAlgorithm macAlgorithm = getMacAlgorithm(secretBytes.length);
        JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(macAlgorithm).build();
        decoderCache.putIfAbsent(cacheKey, decoder);
        return decoderCache.get(cacheKey);
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

    private String getJwtClaim(HttpServletRequest request, String claim) {
        log.debug("Request Header: {}", request.getHeader("authorization"));
        String tokenRequest = request.getHeader("authorization").replace("Bearer ", "");
        String[] chunksToken = tokenRequest.split("\\.");
        Base64.Decoder decoder = Base64.getUrlDecoder();
        String payloadFromToken = new String(decoder.decode(chunksToken[1]));
        String claimJwt = "";
        try {
            Map<String, Object> resultMap = new ObjectMapper().readValue(payloadFromToken, HashMap.class);
            log.debug(resultMap.toString());
            if (resultMap.get(claim) != null) {
                if (resultMap.get(claim) instanceof String) {
                    claimJwt = (String) resultMap.get(claim);
                } else if (resultMap.get(claim) instanceof java.util.List) {
                    java.util.List<String> audienceList = (java.util.List<String>) resultMap.get(claim);
                    if (!audienceList.isEmpty()) {
                        claimJwt = audienceList.getFirst();
                    }
                }
                log.debug("JWT Claim: {} = {}", claim, claimJwt);
            }
        } catch (JsonProcessingException e) {
            log.error(e.getMessage());
        }
        return claimJwt;
    }

    private boolean isTokenDeleted(String tokenId) {
        if (tokenId != null && !tokenId.isEmpty()) {
            Optional<Pat> searchPat = patRepository.findById(UUID.fromString(tokenId));
            Optional<Group> searchGroupToken = teamTokenRepository.findById(UUID.fromString(tokenId));
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