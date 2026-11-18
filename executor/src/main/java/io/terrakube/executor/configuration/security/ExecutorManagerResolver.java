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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

import javax.crypto.SecretKey;

@Builder
@Getter
@Setter
@Slf4j
public class ExecutorManagerResolver implements AuthenticationManagerResolver<HttpServletRequest> {

    static final String EXPECTED_ISSUER = "TERRAKUBE_INTERNAL";
    static final String EXPECTED_SUBJECT = "Terrakube Internal (Token)";
    static final String EXPECTED_AUDIENCE = "TERRAKUBE_INTERNAL";
    static final String ROLE_API_DISPATCHER = "ROLE_API_DISPATCHER";

    private String internalJwtSecret;

    /**
     * Lazily initialised, immutable after first use. The secret key is stable for the
     * lifetime of the process, so there is no need to rebuild the decoder on every request.
     * An {@link AtomicReference} is used instead of a plain {@code volatile} field to
     * satisfy thread-safety requirements without requiring an explicit {@code synchronized}
     * block.
     */
    @Builder.Default
    private final AtomicReference<JwtDecoder> cachedDecoder = new AtomicReference<>();

    @Override
    public AuthenticationManager resolve(HttpServletRequest request) {
        ProviderManager providerManager = null;
        try {
            log.info("Authenticating executor request");
            JwtAuthenticationProvider provider = new JwtAuthenticationProvider(getJwtDecoder());
            provider.setJwtAuthenticationConverter(apiDispatchAuthenticationConverter());
            providerManager = new ProviderManager(provider);
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
        return providerManager;
    }

    static boolean isApiDispatchToken(Jwt jwt) {
        return !jwt.hasClaim("workspaceId")
                && EXPECTED_ISSUER.equals(jwt.getClaimAsString("iss"))
                && EXPECTED_SUBJECT.equals(jwt.getSubject())
                && jwt.getAudience() != null
                && jwt.getAudience().contains(EXPECTED_AUDIENCE);
    }

    static OAuth2TokenValidator<Jwt> apiDispatchTokenValidator() {
        return jwt -> {
            if (jwt.hasClaim("workspaceId")) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token", "Workspace runner tokens are not permitted to dispatch executor jobs", null));
            }
            if (!isApiDispatchToken(jwt)) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token", "Token is not an authorized API scheduler dispatch token", null));
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    static JwtAuthenticationConverter apiDispatchAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            if (isApiDispatchToken(jwt)) {
                return List.of(new SimpleGrantedAuthority(ROLE_API_DISPATCHER));
            }
            return Collections.emptyList();
        });
        return converter;
    }

    private JwtDecoder getJwtDecoder() {
        return cachedDecoder.updateAndGet(existing -> {
            if (existing != null) {
                return existing;
            }
            byte[] secretBytes = Decoders.BASE64URL.decode(internalJwtSecret);
            SecretKey jwtSecretKey = Keys.hmacShaKeyFor(secretBytes);
            MacAlgorithm macAlgorithm = getMacAlgorithm(secretBytes.length);
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                    .macAlgorithm(macAlgorithm)
                    .build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefault(),
                    apiDispatchTokenValidator()
            ));
            return decoder;
        });
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

