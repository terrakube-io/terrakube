package io.terrakube.registry.configuration.authentication.dex;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

public final class RunnerTokenDecoder {

    public static final String ISSUER = "TerrakubeRunner";
    public static final String AUDIENCE = "terrakube-runner";
    private static final List<String> SCOPE_CLAIMS = List.of("organizationId", "workspaceId", "jobId", "stepId");

    private RunnerTokenDecoder() {
    }

    public static RSAPublicKey readPublicKey(String path) throws IOException, GeneralSecurityException {
        String pem = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        String body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
    }

    public static JwtDecoder create(RSAPublicKey publicKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER),
                RunnerTokenDecoder::validate));
        return decoder;
    }

    static OAuth2TokenValidatorResult validate(Jwt jwt) {
        List<String> audience = jwt.getAudience();
        if (audience == null || audience.size() != 1 || !AUDIENCE.equals(audience.get(0))) {
            return failure("runner token audience is invalid");
        }
        for (String claim : SCOPE_CLAIMS) {
            String value = jwt.getClaimAsString(claim);
            if (value == null || value.isBlank()) {
                return failure("runner token is missing claim " + claim);
            }
        }
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("hardened")) || jwt.getExpiresAt() == null) {
            return failure("runner token is not a hardened token");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}
