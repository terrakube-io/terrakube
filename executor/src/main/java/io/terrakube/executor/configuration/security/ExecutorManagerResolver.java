package io.terrakube.executor.configuration.security;

import io.jsonwebtoken.io.Decoders;
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
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;

@Builder
@Getter
@Setter
@Slf4j
public class ExecutorManagerResolver implements AuthenticationManagerResolver<HttpServletRequest> {

    private String internalJwtSecret;

    @Override
    public AuthenticationManager resolve(HttpServletRequest request) {
        ProviderManager providerManager = null;
        try {
            log.info("Authenticating executor request");
            providerManager = new ProviderManager(new JwtAuthenticationProvider(getJwtEncoder()));
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
        return providerManager;
    }

    private JwtDecoder getJwtEncoder() {
        SecretKey jwtSecretKey = new SecretKeySpec(Decoders.BASE64URL.decode(internalJwtSecret), "HMACSHA256");
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

}
