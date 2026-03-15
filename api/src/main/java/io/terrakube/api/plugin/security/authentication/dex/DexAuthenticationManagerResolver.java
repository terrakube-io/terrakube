package io.terrakube.api.plugin.security.authentication.dex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.io.Decoders;
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
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;

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
            providerManager = new ProviderManager(new JwtAuthenticationProvider(JwtDecoders.fromIssuerLocation(federatedIssuer)));
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
                    providerManager = new ProviderManager(new JwtAuthenticationProvider(JwtDecoders.fromIssuerLocation(this.dexIssuerUri)));
                    break;
            }
        }
        return providerManager;
    }

    private JwtDecoder getJwtEncoder(String issuerType) {
        String jwtSecret = (issuerType.equals(jwtTypePat) ? patJwtSecret : internalJwtSecret);
        SecretKey jwtSecretKey = new SecretKeySpec(Decoders.BASE64URL.decode(jwtSecret), "HMACSHA256");
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
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