package io.terrakube.registry.configuration.authentication.dex;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import io.jsonwebtoken.io.Decoders;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Builder
@Getter
@Setter
@Slf4j
public class RegistryAuthenticationManagerResolver implements AuthenticationManagerResolver<HttpServletRequest> {

    private static final String jwtPat ="Terrakube";
    private static final String jwtInternal ="TerrakubeInternal";
    private String internalSecret;
    private String issuerUri;
    private String patSecret;

    @Override
    public AuthenticationManager resolve(HttpServletRequest request) {
        ProviderManager providerManager = null;
        String tokenIssuer = getJwtIssuer(request);
        switch (tokenIssuer) {
            case jwtInternal:
                providerManager = new ProviderManager(new JwtAuthenticationProvider(getJwtEncoder(jwtInternal)));
                break;
            case jwtPat:
                providerManager = new ProviderManager(new JwtAuthenticationProvider(getJwtEncoder(jwtPat)));
                break;
            default:
                providerManager = new ProviderManager(new JwtAuthenticationProvider(JwtDecoders.fromIssuerLocation(this.issuerUri)));
                break;
        }
        return providerManager;
    }

    private String getJwtIssuer(HttpServletRequest request) {
        String token = request.getHeader("authorization").replace("Bearer ", "");
        String[] chunks = token.split("\\.");
        Base64.Decoder decoder = Base64.getUrlDecoder();
        String payload = new String(decoder.decode(chunks[1]));
        String issuer = "";
        try {
            Map<String,Object> result = new ObjectMapper().readValue(payload, HashMap.class);
            issuer = result.get("iss").toString();
        } catch (JsonProcessingException e) {
            log.error(e.getMessage());
        }
        return issuer;
    }

    private JwtDecoder getJwtEncoder(String issuerType) {
        String tokenSecret = (issuerType.equals(jwtPat) ? patSecret : internalSecret);
        SecretKey jwtTokenKey = new SecretKeySpec(Decoders.BASE64URL.decode(tokenSecret), "HMACSHA256");
        return NimbusJwtDecoder.withSecretKey(jwtTokenKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

}
