package io.terrakube.api.plugin.hardened;

import io.jsonwebtoken.Jwts;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ephemeral.EphemeralConfiguration;
import io.terrakube.api.plugin.security.token.RunnerToken;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
public class RunnerTokenService {

    private final HardenedModeProperties properties;
    private final EphemeralConfiguration ephemeralConfiguration;
    private final String dynamicCredentialsPrivateKeyPath;

    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;

    public RunnerTokenService(HardenedModeProperties properties,
                              EphemeralConfiguration ephemeralConfiguration,
                              @Value("${io.terrakube.dynamic.credentials.private-key-path:}") String dynamicCredentialsPrivateKeyPath) {
        this.properties = properties;
        this.ephemeralConfiguration = ephemeralConfiguration;
        this.dynamicCredentialsPrivateKeyPath = dynamicCredentialsPrivateKeyPath;
    }

    @PostConstruct
    void validate() {
        if (!properties.isEnabled()) {
            return;
        }
        require(notBlank(properties.getRunnerTokenPrivateKeyPath()), "TerrakubeRunnerTokenPrivateKeyPath is required");
        require(notBlank(properties.getRunnerTokenPublicKeyPath()), "TerrakubeRunnerTokenPublicKeyPath is required");
        require(!properties.getRunnerTokenPrivateKeyPath().equals(dynamicCredentialsPrivateKeyPath),
                "the runner token key must not be the dynamic credentials key");
        require(properties.getRunnerTokenTtlMinutes() > 0, "TerrakubeRunnerTokenTtlMinutes must be positive");
        require(notBlank(properties.getRunnerServiceAccount()), "TerrakubeRunnerServiceAccount is required");
        require(notBlank(properties.getRunnerConfigMap()), "TerrakubeRunnerConfigMap is required");
        require(notBlank(ephemeralConfiguration.getNamespace()), "ExecutorEphemeralNamespace is required");
        require(notBlank(ephemeralConfiguration.getImage()), "ExecutorEphemeralImage is required");
        Long deadline = ephemeralConfiguration.getActiveDeadlineSeconds();
        require(deadline != null, "ExecutorEphemeralActiveDeadlineSeconds is required so a runner never outlives its token");
        require(deadline <= properties.getRunnerTokenTtlMinutes() * 60L,
                "ExecutorEphemeralActiveDeadlineSeconds must not exceed the runner token lifetime");
        try {
            privateKey = PemKeys.readPrivateKey(properties.getRunnerTokenPrivateKeyPath());
            publicKey = PemKeys.readPublicKey(properties.getRunnerTokenPublicKeyPath());
        } catch (Exception e) {
            throw new IllegalStateException("Hardened mode: unable to load the runner token key pair: " + e.getMessage(), e);
        }
        require(privateKey.getModulus().equals(publicKey.getModulus()), "runner token public and private keys do not match");
        require(!sameKeyAsDynamicCredentials(), "the runner token key must not be the dynamic credentials key");
        require(publicKey.getModulus().bitLength() >= 2048, "runner token key must be at least 2048 bits");
        log.info("Hardened mode enabled: runs are forced onto ephemeral runners using service account {}",
                properties.getRunnerServiceAccount());
    }

    private boolean sameKeyAsDynamicCredentials() {
        if (!notBlank(dynamicCredentialsPrivateKeyPath)) {
            return false;
        }
        try {
            return PemKeys.readPrivateKey(dynamicCredentialsPrivateKeyPath).getModulus().equals(privateKey.getModulus());
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public RSAPublicKey getPublicKey() {
        return publicKey;
    }

    public String mint(String organizationId, String workspaceId, String jobId, String stepId, String flowType) {
        if (!properties.isEnabled() || privateKey == null) {
            throw new IllegalStateException("Runner tokens are only issued in hardened mode");
        }
        Instant now = Instant.now();
        return Jwts.builder()
                .header().type("JWT").and()
                .issuer(RunnerToken.ISSUER)
                .audience().add(RunnerToken.AUDIENCE).and()
                .subject(String.format("runner:%s:%s", jobId, stepId))
                .id(UUID.randomUUID().toString())
                .claim("name", "TerrakubeRunner Job")
                .claim(RunnerToken.CLAIM_ORGANIZATION_ID, organizationId)
                .claim(RunnerToken.CLAIM_WORKSPACE_ID, workspaceId)
                .claim(RunnerToken.CLAIM_JOB_ID, jobId)
                .claim(RunnerToken.CLAIM_STEP_ID, stepId)
                .claim(RunnerToken.CLAIM_HARDENED, true)
                .claim(RunnerToken.CLAIM_FLOW, flowType)
                .issuedAt(Date.from(now))
                .notBefore(Date.from(now.minus(1, ChronoUnit.MINUTES)))
                .expiration(Date.from(now.plus(properties.getRunnerTokenTtlMinutes(), ChronoUnit.MINUTES)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Hardened mode: " + message);
        }
    }
}
