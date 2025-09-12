package io.terrakube.api.plugin.security.authentication.dex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.io.Decoders;
import io.terrakube.api.repository.PatRepository;
import io.terrakube.api.repository.TeamTokenRepository;
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

    private static final String jwtTypePat="Terrakube";
    private static final String jwtTypeInternal="TerrakubeInternal";
    private String dexIssuerUri;
    private String patJwtSecret;
    private String internalJwtSecret;
    private PatRepository patRepository;
    private TeamTokenRepository teamTokenRepository;

    @Override
    public AuthenticationManager resolve(HttpServletRequest request) {
        ProviderManager providerManager = null;
        String issuer = "";
        try{
            issuer = getJwtClaim(request, "iss");
            if (isTokenDeleted(getJwtClaim(request, "jti"))){
                //FORCE TOKEN TO USE INTERNAL AUTH SO IT CAN ALWAYS FAIL
                issuer = jwtTypeInternal;
            }
        }catch (Exception ex){
            log.info(ex.getMessage());
        }
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
        return providerManager;
    }

    private JwtDecoder getJwtEncoder(String issuerType) {
        String jwtSecret = (issuerType.equals(jwtTypePat) ? patJwtSecret : internalJwtSecret);
        SecretKey jwtSecretKey = new SecretKeySpec(Decoders.BASE64URL.decode(jwtSecret), "HMACSHA256");
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private String getJwtClaim(HttpServletRequest request, String claim) {
        String tokenRequest = request.getHeader("authorization").replace("Bearer ", "");
        String[] chunksToken = tokenRequest.split("\\.");
        Base64.Decoder decoder = Base64.getDecoder();
        String payloadFromToken = new String(decoder.decode(chunksToken[1]));
        String claimJwt = "";
        try {
            Map<String,Object> resultMap = new ObjectMapper().readValue(payloadFromToken, HashMap.class);
            claimJwt = resultMap.get(claim).toString();
        } catch (JsonProcessingException e) {
            log.error(e.getMessage());
        }
        return claimJwt;
    }

    private boolean isTokenDeleted(String tokenId) {
        if (tokenId != null) {
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