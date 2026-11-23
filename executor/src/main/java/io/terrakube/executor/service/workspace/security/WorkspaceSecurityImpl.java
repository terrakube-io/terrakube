package io.terrakube.executor.service.workspace.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import io.terrakube.client.spring.autoconfigure.RestClientProperties;
import io.terrakube.executor.configuration.ProviderMirrorProperties;

import io.terrakube.executor.configuration.security.InternalSecretLoader;

import javax.crypto.SecretKey;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Slf4j
@Service
public class WorkspaceSecurityImpl implements WorkspaceSecurity {

    private static final String ISSUER = "TerrakubeInternal";
    private static final String SUBJECT = "TerrakubeInternal (EXECUTOR)";
    private static final String EMAIL = "no-reply@terrakube.io";
    private static final String NAME = "TerrakubeInternal Client";
    private static final String CREDENTIALS_FILE_NAME = "/.terraformrc";
    private static final String CREDENTIALS_CONTENT = "credentials \"%s\" {\n" +
            "token = \"%s\"" +
            "}";

    RestClientProperties clientProperties;
    String registryDomain;

    String apiUrl;

    String internalSecret;

    private final boolean providerMirrorEnabled;
    private final boolean providerMirrorOnly;
    private final String providerMirrorRegistryUrl;

    @Autowired
    public WorkspaceSecurityImpl(
            RestClientProperties restClientProperties,
            @Value("${io.terrakube.registry.domain}") String registryDomain,
            @Value("${io.terrakube.api.url}") String apiUrl,
            @Value("${io.terrakube.public-url:}") String publicUrl,
            InternalSecretLoader secretLoader,
            ProviderMirrorProperties providerMirror) {
        this.clientProperties = restClientProperties;
        this.registryDomain = resolveRegistryDomain(registryDomain, publicUrl);
        this.apiUrl = apiUrl;
        this.internalSecret = secretLoader.getInternalSecret();
        this.providerMirrorEnabled = providerMirror.isEnabled();
        this.providerMirrorOnly = providerMirror.isOnly();
        this.providerMirrorRegistryUrl = resolveMirrorRegistryUrl(providerMirror.getRegistryUrl(), registryDomain, publicUrl);
    }

    public WorkspaceSecurityImpl(
            RestClientProperties restClientProperties,
            String registryDomain,
            String apiUrl,
            String publicUrl,
            InternalSecretLoader secretLoader) {
        this(restClientProperties, registryDomain, apiUrl, publicUrl, secretLoader, new ProviderMirrorProperties());
    }

    public WorkspaceSecurityImpl(RestClientProperties restClientProperties, String registryDomain, String apiUrl,
                                 InternalSecretLoader secretLoader) {
        this(restClientProperties, registryDomain, apiUrl, "", secretLoader);
    }

    public WorkspaceSecurityImpl(RestClientProperties restClientProperties, String registryDomain, String apiUrl, String internalSecret) {
        this(restClientProperties, registryDomain, apiUrl, "", new InternalSecretLoader(internalSecret, null));
    }

    /**
     * The registry base URL the provider mirror hangs off: the explicit setting, else https://TerrakubeRegistryDomain,
     * else the single-domain public URL's /api/private-registry. Empty when none can be worked out.
     */
    static String resolveMirrorRegistryUrl(String explicit, String registryDomain, String publicUrl) {
        String url = "";
        if (explicit != null && !explicit.isBlank()) {
            url = explicit.trim();
        } else if (registryDomain != null && !registryDomain.isBlank()) {
            url = "https://" + registryDomain.trim();
        } else if (publicUrl != null && !publicUrl.isBlank()) {
            url = publicUrl.trim() + "/api/private-registry";
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** The CLI configuration block for the mirror, or an empty string when it is off or cannot be built. */
    String providerInstallationBlock(String organizationName) {
        if (!providerMirrorEnabled) {
            return "";
        }
        if (organizationName == null || organizationName.isBlank() || providerMirrorRegistryUrl.isEmpty()) {
            log.warn("Provider mirror is enabled but the organization name or the registry URL is unknown: providers are installed from their origin");
            return "";
        }
        // The name is free text, so encode it: nothing in it can then close the HCL string or start an interpolation.
        String encoded = URLEncoder.encode(organizationName, StandardCharsets.UTF_8).replace("+", "%20");
        StringBuilder block = new StringBuilder("provider_installation {\n")
                .append("  network_mirror {\n")
                .append("    url = \"").append(providerMirrorRegistryUrl)
                .append("/terraform/providers/mirror/v1/").append(encoded).append("/\"\n")
                .append("  }\n");
        if (!providerMirrorOnly) {
            block.append("  direct {\n  }\n");
        }
        return block.append("}\n").toString();
    }

    /** TerrakubeRegistryDomain, or the single-domain public URL's host when it is unset. */
    static String resolveRegistryDomain(String registryDomain, String publicUrl) {
        if (registryDomain != null && !registryDomain.isBlank()) {
            return registryDomain.trim();
        }
        if (publicUrl != null && !publicUrl.isBlank()) {
            return java.net.URI.create(publicUrl.trim()).getRawAuthority();
        }
        throw new IllegalStateException("Set TerrakubeRegistryDomain or TerrakubePublicUrl");
    }

    @Override
    public String generateAccessToken(String workspaceId) {
        log.debug("Generate Dex Authentication Private Token");
        return generateAccessToken(60, null, workspaceId, null, null);
    }

    @Override
    public String generateAccessToken(int minutes) {
        return generateAccessToken(minutes, null, null, null, null);
    }

    @Override
    public String generateAccessToken(int minutes, String workspaceId) {
        return generateAccessToken(minutes, null, workspaceId, null, null);
    }

    @Override
    public String generateAccessToken(int minutes, String organizationId, String workspaceId, String jobId, String stepId) {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(this.internalSecret));

        var builder = Jwts.builder()
                .setHeaderParam("typ", "JWT")
                .setIssuer(WorkspaceSecurityImpl.ISSUER)
                .setSubject(WorkspaceSecurityImpl.SUBJECT)
                .setAudience(WorkspaceSecurityImpl.ISSUER)
                .claim("email", WorkspaceSecurityImpl.EMAIL)
                .claim("email_verified", true)
                .claim("name", WorkspaceSecurityImpl.NAME);

        if (organizationId != null && !organizationId.isBlank()) {
            builder.claim("organizationId", organizationId);
        }

        if (workspaceId != null && !workspaceId.isBlank()) {
            builder.claim("workspaceId", workspaceId);
        }

        if (jobId != null && !jobId.isBlank()) {
            builder.claim("jobId", jobId);
            String jti = (stepId != null && !stepId.isBlank()) ? (jobId + "-" + stepId) : jobId;
            builder.setId(jti);
        }

        if (stepId != null && !stepId.isBlank()) {
            builder.claim("stepId", stepId);
        }

        return builder.setIssuedAt(Date.from(Instant.now()))
                .setExpiration(Date.from(Instant.now().plus(minutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    @Override
    public void addTerraformCredentials(String workspaceId) {
        addTerraformCredentials(null, workspaceId, null, null);
    }

    @Override
    public void addTerraformCredentials(String organizationId, String workspaceId, String jobId, String stepId) {
        addTerraformCredentials(organizationId, null, workspaceId, jobId, stepId);
    }

    @Override
    public void addTerraformCredentials(String organizationId, String organizationName, String workspaceId,
                                        String jobId, String stepId) {

        String token = generateAccessToken(60, organizationId, workspaceId, jobId, stepId);
        String credentialFileContent = String.format(CREDENTIALS_CONTENT, registryDomain, token);
        String credentialFileContent2 = "";
        try {
            URL api = new URL(apiUrl);
            String apiHost = api.getHost();
            // On a single-domain host the registry and the API share one hostname: one block is enough.
            // registryDomain may carry a port (from the public URL's authority), so compare both forms.
            if (!apiHost.equalsIgnoreCase(registryDomain) && !api.getAuthority().equalsIgnoreCase(registryDomain)) {
                credentialFileContent2 = String.format(CREDENTIALS_CONTENT, apiHost, token);
            }
        } catch (MalformedURLException e) {
            log.error(e.getMessage());
        }

        try {
            File credentialFile = new File(
                    FilenameUtils.separatorsToSystem(
                            FileUtils.getUserDirectoryPath().concat(CREDENTIALS_FILE_NAME)
                    )
            );
            synchronized (this) {
                FileUtils.writeStringToFile(credentialFile, credentialFileContent, Charset.defaultCharset(), false);
                FileUtils.writeStringToFile(credentialFile, "\n", Charset.defaultCharset(), true);
                FileUtils.writeStringToFile(credentialFile, credentialFileContent2, Charset.defaultCharset(), true);
                String mirrorBlock = providerInstallationBlock(organizationName);
                if (!mirrorBlock.isEmpty()) {
                    FileUtils.writeStringToFile(credentialFile, "\n" + mirrorBlock, Charset.defaultCharset(), true);
                }

            }
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    /**
     * Purges ~/.terraformrc created for the executing job.
     * In Terrakube, persistent executor instances process a single job at a time due to capacity limits
     * and concurrency gating (ExecutorCapacityGate), and ephemeral executor runs use isolated 1:1
     * Kubernetes Job pods. Therefore, ~/.terraformrc belongs exclusively to the currently running
     * job and deleting it post-execution ensures no residual tokens linger on disk.
     */
    @Override
    public void cleanTerraformCredentials() {
        try {
            File credentialFile = new File(
                    FilenameUtils.separatorsToSystem(
                            FileUtils.getUserDirectoryPath().concat(CREDENTIALS_FILE_NAME)
                    )
            );
            synchronized (this) {
                if (credentialFile.exists()) {
                    FileUtils.deleteQuietly(credentialFile);
                    log.info("Terraform credentials file deleted: {}", credentialFile.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            log.error("Failed to clean terraform credentials: {}", e.getMessage());
        }
    }
}
