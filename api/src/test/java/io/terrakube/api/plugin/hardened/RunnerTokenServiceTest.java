package io.terrakube.api.plugin.hardened;

import io.jsonwebtoken.Jwts;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ephemeral.EphemeralConfiguration;
import io.terrakube.api.plugin.security.token.InternalTokenClassifier;
import io.terrakube.api.plugin.security.token.InternalTokenType;
import io.terrakube.api.plugin.security.token.RunnerToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class RunnerTokenServiceTest {

    @TempDir
    Path dir;

    HardenedModeProperties properties;
    EphemeralConfiguration ephemeral;
    KeyPair keyPair;

    @BeforeEach
    void setup() throws Exception {
        keyPair = rsa();
        properties = new HardenedModeProperties();
        properties.setEnabled(true);
        properties.setRunnerTokenPrivateKeyPath(write("private.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded()).toString());
        properties.setRunnerTokenPublicKeyPath(write("public.pem", "PUBLIC KEY", keyPair.getPublic().getEncoded()).toString());
        properties.setRunnerServiceAccount("terrakube-runner");
        properties.setRunnerConfigMap("terrakube-runner-config");
        ephemeral = new EphemeralConfiguration();
        ephemeral.setNamespace("terrakube");
        ephemeral.setImage("executor:test");
        ephemeral.setActiveDeadlineSeconds(3600L);
    }

    @Test
    void disabledModeSkipsValidationAndRefusesToMint() {
        properties.setEnabled(false);
        properties.setRunnerTokenPrivateKeyPath(null);
        RunnerTokenService service = service("");
        service.validate();
        assertThrows(IllegalStateException.class, () -> service.mint("o", "w", "1", "s", "terraformPlan"));
    }

    @Test
    void mintedTokenIsAcceptedAndScoped() {
        RunnerTokenService service = service("");
        service.validate();
        String token = service.mint("org-1", "ws-1", "42", "step-1", "terraformApply");

        Jwt jwt = RunnerTokenDecoder.create(service.getPublicKey()).decode(token);
        assertEquals(RunnerToken.ISSUER, jwt.getClaimAsString("iss"));
        assertEquals("ws-1", jwt.getClaimAsString(RunnerToken.CLAIM_WORKSPACE_ID));
        assertEquals("42", jwt.getClaimAsString(RunnerToken.CLAIM_JOB_ID));
        assertTrue(jwt.getClaimAsBoolean(RunnerToken.CLAIM_HARDENED));
        assertEquals("terraformApply", jwt.getClaimAsString(RunnerToken.CLAIM_FLOW));
        assertEquals(InternalTokenType.RUNNER_JOB, InternalTokenClassifier.classify(jwt.getClaims()));
        assertTrue(RunnerToken.isRunner(jwt.getClaims()));
    }

    @Test
    void decoderRejectsForeignOrWeakTokens() throws Exception {
        RunnerTokenService service = service("");
        service.validate();
        JwtDecoder decoder = RunnerTokenDecoder.create(service.getPublicKey());

        assertThrows(JwtException.class, () -> decoder.decode(token(keyPair, "other-audience", true, "ws")));
        assertThrows(JwtException.class, () -> decoder.decode(token(keyPair, RunnerToken.AUDIENCE, false, "ws")));
        assertThrows(JwtException.class, () -> decoder.decode(token(keyPair, RunnerToken.AUDIENCE, true, null)));
        assertThrows(JwtException.class, () -> decoder.decode(token(rsa(), RunnerToken.AUDIENCE, true, "ws")));
        assertDoesNotThrow(() -> decoder.decode(token(keyPair, RunnerToken.AUDIENCE, true, "ws")));
    }

    @Test
    void validationFailsClosed() {
        assertInvalid(() -> properties.setRunnerServiceAccount(" "));
        assertInvalid(() -> properties.setRunnerConfigMap(null));
        assertInvalid(() -> properties.setRunnerTokenPublicKeyPath(dir.resolve("missing.pem").toString()));
        assertInvalid(() -> ephemeral.setActiveDeadlineSeconds(null));
        assertInvalid(() -> ephemeral.setActiveDeadlineSeconds(properties.getRunnerTokenTtlMinutes() * 60L + 1));
        assertInvalid(() -> ephemeral.setImage(""));
    }

    @Test
    void refusesTheDynamicCredentialsKey() {
        RunnerTokenService service = service(properties.getRunnerTokenPrivateKeyPath());
        assertThrows(IllegalStateException.class, service::validate);
    }

    @Test
    void refusesMismatchedKeyPair() throws Exception {
        properties.setRunnerTokenPublicKeyPath(write("other.pem", "PUBLIC KEY", rsa().getPublic().getEncoded()).toString());
        assertThrows(IllegalStateException.class, service("")::validate);
    }

    private void assertInvalid(Runnable mutation) {
        try {
            setup();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        mutation.run();
        assertThrows(IllegalStateException.class, service("")::validate);
    }

    private RunnerTokenService service(String dynamicCredentialsKeyPath) {
        return new RunnerTokenService(properties, ephemeral, dynamicCredentialsKeyPath);
    }

    private static String token(KeyPair keys, String audience, boolean hardened, String workspaceId) {
        var builder = Jwts.builder()
                .issuer(RunnerToken.ISSUER)
                .audience().add(audience).and()
                .claim("organizationId", "org")
                .claim("jobId", "1")
                .claim("stepId", "s")
                .claim("flow", "terraformPlan")
                .claim("hardened", hardened)
                .expiration(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)));
        if (workspaceId != null) {
            builder.claim("workspaceId", workspaceId);
        }
        return builder.signWith(keys.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private Path write(String name, String type, byte[] der) throws Exception {
        String pem = "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
        return Files.writeString(dir.resolve(name), pem);
    }
}
