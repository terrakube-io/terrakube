package io.terrakube.api.plugin.hardened;

import io.terrakube.api.plugin.security.token.RunnerToken;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.interfaces.RSAPublicKey;
import java.util.List;

public final class RunnerTokenDecoder {

    private RunnerTokenDecoder() {
    }

    public static JwtDecoder create(RSAPublicKey publicKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(RunnerToken.ISSUER),
                RunnerTokenDecoder::validateAudienceAndScope);
        decoder.setJwtValidator(validator);
        return decoder;
    }

    static OAuth2TokenValidatorResult validateAudienceAndScope(Jwt jwt) {
        List<String> audience = jwt.getAudience();
        if (audience == null || audience.size() != 1 || !RunnerToken.AUDIENCE.equals(audience.get(0))) {
            return failure("runner token audience is invalid");
        }
        for (String claim : RunnerToken.REQUIRED_SCOPE_CLAIMS) {
            String value = jwt.getClaimAsString(claim);
            if (value == null || value.isBlank()) {
                return failure("runner token is missing claim " + claim);
            }
        }
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean(RunnerToken.CLAIM_HARDENED))) {
            return failure("runner token is not a hardened token");
        }
        if (jwt.getExpiresAt() == null) {
            return failure("runner token must expire");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}
