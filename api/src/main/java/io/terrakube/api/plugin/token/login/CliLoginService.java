package io.terrakube.api.plugin.token.login;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.token.pat.PatService;
import io.terrakube.api.repository.CliAuthSessionRepository;
import io.terrakube.api.rs.token.login.CliAuthSession;
import io.terrakube.api.rs.token.login.CliAuthSessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CliLoginService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long SESSION_TTL_MS = 10 * 60 * 1000L;
    private static final long CODE_TTL_MS = 60 * 1000L;

    private final CliAuthSessionRepository repository;
    private final DexExchangeClient dexExchangeClient;
    private final PatService patService;
    private final LoopbackRedirectUriValidator redirectUriValidator;
    private final TerraformLoginProperties loginProperties;
    private final ObjectMapper objectMapper;

    public record AuthorizeRequest(String clientId, String redirectUri, String responseType,
                                   String codeChallenge, String codeChallengeMethod, String state) {
    }

    public record TokenRequest(String grantType, String code, String codeVerifier,
                               String redirectUri, String clientId) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }

    @Transactional
    public String startAuthorization(AuthorizeRequest req) {
        if (!TerraformLoginProperties.CLIENT_ID.equals(req.clientId())) {
            throw new BrokerBadRequestException("unknown client_id");
        }
        if (!"code".equals(req.responseType())) {
            throw new BrokerBadRequestException("response_type must be code");
        }
        if (!"S256".equals(req.codeChallengeMethod())) {
            throw new BrokerBadRequestException("code_challenge_method must be S256");
        }
        if (req.codeChallenge() == null || req.codeChallenge().isBlank()) {
            throw new BrokerBadRequestException("code_challenge is required");
        }
        if (req.state() == null || req.state().isBlank()) {
            throw new BrokerBadRequestException("state is required");
        }
        redirectUriValidator.validate(req.redirectUri());

        CliAuthSession session = new CliAuthSession();
        session.setStatus(CliAuthSessionStatus.PENDING_IDP);
        session.setCliRedirectUri(req.redirectUri());
        session.setCliCodeChallenge(req.codeChallenge());
        session.setCliState(req.state());
        session.setDexCodeVerifier(PkceUtil.generateCodeVerifier());
        session.setExpiresAt(new Date(System.currentTimeMillis() + SESSION_TTL_MS));
        session = repository.save(session);

        return dexExchangeClient.buildAuthorizeRedirect(
            session.getId().toString(),
            PkceUtil.codeChallengeS256(session.getDexCodeVerifier()));
    }

    @Transactional
    public String handleCallback(String code, String state, String iss) {
        CliAuthSession session = loadActive(parseId(state));
        if (session.getStatus() != CliAuthSessionStatus.PENDING_IDP) {
            throw new BrokerBadRequestException("session is not awaiting identity provider");
        }
        if (iss != null && !iss.isBlank() && !dexExchangeClient.issuerUri().equals(iss)) {
            fail(session);
            throw new BrokerBadRequestException("issuer mismatch");
        }
        DexIdentity identity;
        try {
            identity = dexExchangeClient.exchange(code, session.getDexCodeVerifier());
        } catch (RuntimeException e) {
            fail(session);
            throw e;
        }
        session.setIdentityEmail(identity.email());
        session.setIdentityName(identity.name());
        try {
            session.setIdentityGroups(objectMapper.writeValueAsString(
                identity.groups() == null ? List.of() : identity.groups()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        session.setDexCodeVerifier(null);
        session.setStatus(CliAuthSessionStatus.PENDING_CONSENT);
        repository.save(session);
        return session.getId().toString();
    }

    @Transactional(readOnly = true)
    public CliAuthSession requireConsentSession(String sessionId) {
        CliAuthSession session = loadActive(parseId(sessionId));
        if (session.getStatus() != CliAuthSessionStatus.PENDING_CONSENT) {
            throw new BrokerBadRequestException("session is not awaiting consent");
        }
        return session;
    }

    @Transactional
    public String authorize(String sessionId, int days, String tokenName) {
        CliAuthSession session = requireConsentSession(sessionId);
        if (days < 1 || days > loginProperties.getMaxDays()) {
            throw new BrokerBadRequestException("days must be between 1 and " + loginProperties.getMaxDays());
        }
        String code = randomToken();
        session.setAuthCodeHash(sha256Hex(code));
        session.setChosenDays(days);
        session.setChosenName(sanitizeTokenName(tokenName));
        session.setStatus(CliAuthSessionStatus.CODE_ISSUED);
        session.setCodeExpiresAt(new Date(System.currentTimeMillis() + CODE_TTL_MS));
        repository.save(session);
        return session.getCliRedirectUri() + "?code=" + urlEnc(code) + "&state=" + urlEnc(session.getCliState());
    }

    private static String sanitizeTokenName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip().replaceAll("[\\p{Cntrl}]", "");
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }

    @Transactional
    public String deny(String sessionId) {
        CliAuthSession session = requireConsentSession(sessionId);
        session.setStatus(CliAuthSessionStatus.DENIED);
        repository.save(session);
        return session.getCliRedirectUri() + "?error=access_denied&state=" + urlEnc(session.getCliState());
    }

    @Transactional
    public TokenResponse exchangeToken(TokenRequest req) {
        if (!"authorization_code".equals(req.grantType())) {
            throw new BrokerBadRequestException("invalid_grant");
        }
        CliAuthSession session = repository.findByAuthCodeHash(sha256Hex(req.code()))
            .orElseThrow(() -> new BrokerBadRequestException("invalid_grant"));
        boolean valid = session.getStatus() == CliAuthSessionStatus.CODE_ISSUED
            && session.getCodeExpiresAt() != null
            && session.getCodeExpiresAt().after(new Date())
            && session.getCliRedirectUri().equals(req.redirectUri())
            && TerraformLoginProperties.CLIENT_ID.equals(req.clientId())
            && PkceUtil.verifyS256(req.codeVerifier(), session.getCliCodeChallenge());
        if (!valid) {
            throw new BrokerBadRequestException("invalid_grant");
        }

        List<String> groups;
        try {
            groups = session.getIdentityGroups() == null ? List.of()
                : objectMapper.readValue(session.getIdentityGroups(), new TypeReference<List<String>>() {
                });
        } catch (Exception e) {
            groups = List.of();
        }
        String description = session.getChosenName() != null
            ? session.getChosenName()
            : "terraform login " + DateTimeFormatter.ISO_LOCAL_DATE
                .withZone(ZoneOffset.UTC).format(Instant.now());
        String jws = patService.createToken(session.getChosenDays(), description,
            session.getIdentityName(), session.getIdentityEmail(), groups, "CLI_LOGIN");
        if (jws == null || jws.isBlank()) {
            throw new BrokerUpstreamException("token generation failed");
        }
        // The token is minted from an unauthenticated endpoint; attribute the pat row to the
        // user who authenticated upstream so the audit trail shows who authorized it.
        patService.attributeTo(jtiOf(jws), session.getIdentityEmail());

        session.setStatus(CliAuthSessionStatus.EXCHANGED);
        repository.save(session);
        return new TokenResponse(jws, "Bearer", session.getChosenDays() * 86400L);
    }

    private CliAuthSession loadActive(UUID id) {
        CliAuthSession session = repository.findById(id)
            .orElseThrow(() -> new BrokerBadRequestException("session not found"));
        if (session.getExpiresAt().before(new Date())) {
            throw new BrokerBadRequestException("session expired");
        }
        return session;
    }

    private void fail(CliAuthSession session) {
        session.setStatus(CliAuthSessionStatus.FAILED);
        session.setDexCodeVerifier(null);
        repository.save(session);
    }

    private static UUID parseId(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            throw new BrokerBadRequestException("session not found");
        }
    }

    private static String randomToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static UUID jtiOf(String jws) {
        String payload = new String(Base64.getUrlDecoder().decode(jws.split("\\.")[1]), StandardCharsets.UTF_8);
        return UUID.fromString(payload.replaceAll(".*\"jti\":\"([^\"]+)\".*", "$1"));
    }

    static String sha256Hex(String input) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String urlEnc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
