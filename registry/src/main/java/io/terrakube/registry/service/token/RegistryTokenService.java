package io.terrakube.registry.service.token;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Slf4j
@Service
public class RegistryTokenService {

    private static final String ISSUER = "TerrakubeInternal";
    private static final String SUBJECT = "TerrakubeInternal (REGISTRY)";
    private static final String EMAIL = "no-reply@terrakube.io";
    private static final String NAME = "TerrakubeInternal Client";

    private final String internalSecret;

    public RegistryTokenService(@Value("${io.terrakube.token.internal:}") String internalSecret) {
        this.internalSecret = internalSecret;
    }

    public String generateInternalToken() {
        if (internalSecret == null || internalSecret.isBlank()) {
            log.warn("Internal secret is not configured for RegistryTokenService");
            return "";
        }

        byte[] secretBytes;
        try {
            secretBytes = Decoders.BASE64URL.decode(internalSecret);
        } catch (Exception e) {
            secretBytes = internalSecret.getBytes(StandardCharsets.UTF_8);
        }
        SecretKey key = Keys.hmacShaKeyFor(secretBytes);

        return Jwts.builder()
                .header().add("typ", "JWT").and()
                .issuer(ISSUER)
                .subject(SUBJECT)
                .audience().add(ISSUER).and()
                .claim("email", EMAIL)
                .claim("email_verified", true)
                .claim("name", NAME)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(10, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }
}
