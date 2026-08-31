package io.terrakube.executor.configuration.security;

import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
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

@Builder
@Getter
@Setter
@Slf4j
public class ExecutorManagerResolver implements AuthenticationManagerResolver<HttpServletRequest> {

    private String internalJwtSecret;

    /**
     * Lazily initialised, immutable after first use. The secret key is stable for the
     * lifetime of the process, so there is no need to rebuild the decoder on every request.
     */
    @Builder.Default
    private volatile JwtDecoder cachedDecoder = null;

    @Override
    public AuthenticationManager resolve(HttpServletRequest request) {
        ProviderManager providerManager = null;
        try {
            log.info("Authenticating executor request");
            providerManager = new ProviderManager(new JwtAuthenticationProvider(getJwtDecoder()));
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
        return providerManager;
    }

    private JwtDecoder getJwtDecoder() {
        if (cachedDecoder == null) {
            synchronized (this) {
                if (cachedDecoder == null) {
                    byte[] secretBytes = Decoders.BASE64URL.decode(internalJwtSecret);
                    SecretKey jwtSecretKey = Keys.hmacShaKeyFor(secretBytes);
                    MacAlgorithm macAlgorithm = getMacAlgorithm(secretBytes.length);
                    cachedDecoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(macAlgorithm).build();
                }
            }
        }
        return cachedDecoder;
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

}

