package io.terrakube.executor.service.hardened;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.configuration.security.InternalSecretLoader;
import io.terrakube.executor.plugin.tfstate.configuration.TerraformStateProperties;
import io.terrakube.executor.plugin.tfstate.configuration.TerraformStateType;
import io.terrakube.executor.service.mode.TerraformJob;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@Slf4j
@Component
public class HardenedRunnerGuard {

    static final List<String> FORBIDDEN_ENVIRONMENT = List.of(
            "InternalSecret", "InternalSecretFile",
            "AwsTerraformStateAccessKey", "AwsTerraformStateSecretKey",
            "AzureTerraformStateStorageAccessKey", "GcpTerraformStateCredentials",
            "TerrakubeRedisHostname", "TerrakubeRedisPassword",
            "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
            "AWS_ROLE_ARN", "AWS_WEB_IDENTITY_TOKEN_FILE",
            "AWS_CONTAINER_CREDENTIALS_FULL_URI", "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI",
            "AWS_CONTAINER_AUTHORIZATION_TOKEN", "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE",
            "GOOGLE_APPLICATION_CREDENTIALS",
            "AZURE_CLIENT_SECRET", "AZURE_CLIENT_CERTIFICATE_PATH", "AZURE_FEDERATED_TOKEN_FILE",
            "ARM_CLIENT_SECRET", "ARM_CLIENT_CERTIFICATE_PATH", "ARM_OIDC_TOKEN", "ARM_OIDC_TOKEN_FILE_PATH",
            "ARM_ACCESS_KEY", "ARM_SAS_TOKEN", "GOOGLE_CREDENTIALS", "GOOGLE_OAUTH_ACCESS_TOKEN",
            "VAULT_TOKEN", "KUBECONFIG");

    static final List<String> FORBIDDEN_FILES = List.of(
            "/var/run/secrets/kubernetes.io/serviceaccount/token",
            "/var/run/secrets/eks.amazonaws.com/serviceaccount/token",
            "/var/run/secrets/azure/tokens/azure-identity-token",
            "/var/run/secrets/pods.eks.amazonaws.com/serviceaccount/eks-pod-identity-token",
            "/var/run/secrets/tokens/gcp-ksa/token");

    static final List<String> METADATA_ENDPOINTS = List.of("169.254.169.254", "169.254.170.2", "169.254.170.23", "fd00:ec2::254");

    private final ExecutorFlagsProperties flags;
    private final TerraformStateProperties terraformStateProperties;
    private final InternalSecretLoader internalSecretLoader;
    private final boolean logViaApi;
    private final Function<String, String> environment;
    private final Function<String, Boolean> fileExists;
    private final Function<String, Boolean> metadataReachable;

    @Autowired
    public HardenedRunnerGuard(ExecutorFlagsProperties flags, TerraformStateProperties terraformStateProperties,
                               InternalSecretLoader internalSecretLoader,
                               @Value("${io.executor.log-via-api:false}") boolean logViaApi) {
        this(flags, terraformStateProperties, internalSecretLoader, logViaApi, System::getenv,
                path -> new File(path).exists(), host -> canConnect(host, 80));
    }

    HardenedRunnerGuard(ExecutorFlagsProperties flags, TerraformStateProperties terraformStateProperties,
                        InternalSecretLoader internalSecretLoader, boolean logViaApi,
                        Function<String, String> environment, Function<String, Boolean> fileExists,
                        Function<String, Boolean> metadataReachable) {
        this.flags = flags;
        this.terraformStateProperties = terraformStateProperties;
        this.internalSecretLoader = internalSecretLoader;
        this.logViaApi = logViaApi;
        this.environment = environment;
        this.fileExists = fileExists;
        this.metadataReachable = metadataReachable;
    }

    @PostConstruct
    void refusePersistentHardenedExecutor() {
        if (flags.isHardened() && !flags.isEphemeral()) {
            throw new IllegalStateException("Hardened mode only runs on ephemeral runners, refusing to start a persistent executor");
        }
    }

    public List<String> violations(TerraformJob job) {
        List<String> violations = new ArrayList<>();
        if (!flags.isEphemeral()) {
            violations.add("runner is not ephemeral");
        }
        if (terraformStateProperties == null || terraformStateProperties.getType() != TerraformStateType.ApiTerraformStateImpl) {
            violations.add("TerraformStateType must be ApiTerraformStateImpl");
        }
        if (!logViaApi) {
            violations.add("LogViaApi must be true");
        }
        String internalSecret = internalSecretLoader == null ? null : internalSecretLoader.getInternalSecret();
        if (internalSecret != null && !internalSecret.isBlank()) {
            violations.add("an internal secret is configured");
        }
        for (String name : FORBIDDEN_ENVIRONMENT) {
            String value = environment.apply(name);
            if (value != null && !value.isBlank()) {
                violations.add("environment variable " + name + " is set");
            }
        }
        for (String path : FORBIDDEN_FILES) {
            if (Boolean.TRUE.equals(fileExists.apply(path))) {
                violations.add("credential file " + path + " is mounted");
            }
        }
        for (String endpoint : METADATA_ENDPOINTS) {
            if (Boolean.TRUE.equals(metadataReachable.apply(endpoint))) {
                violations.add("the cloud metadata endpoint " + endpoint + " is reachable");
            }
        }
        violations.addAll(tokenViolations(job));
        return violations;
    }

    static List<String> tokenViolations(TerraformJob job) {
        if (job == null || job.getRunnerToken() == null || job.getRunnerToken().isBlank()) {
            return List.of("the job carries no runner token");
        }
        JsonNode claims = claims(job.getRunnerToken());
        if (claims == null) {
            return List.of("the runner token is malformed");
        }
        List<String> violations = new ArrayList<>();
        if (!claims.path("hardened").asBoolean(false)) {
            violations.add("the runner token is not a hardened token");
        }
        Map<String, String> expected = Map.of(
                "organizationId", Objects.toString(job.getOrganizationId(), ""),
                "workspaceId", Objects.toString(job.getWorkspaceId(), ""),
                "jobId", Objects.toString(job.getJobId(), ""),
                "stepId", Objects.toString(job.getStepId(), ""));
        expected.forEach((claim, value) -> {
            if (!claims.path(claim).asText("").equals(value)) {
                violations.add("the runner token " + claim + " does not match the job");
            }
        });
        return violations;
    }

    public static boolean isHardenedToken(String token) {
        JsonNode claims = token == null ? null : claims(token);
        return claims != null && claims.path("hardened").asBoolean(false);
    }

    private static JsonNode claims(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new ObjectMapper().readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    static boolean canConnect(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
