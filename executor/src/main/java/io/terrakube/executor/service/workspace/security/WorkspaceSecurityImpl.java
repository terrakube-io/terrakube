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

import io.terrakube.executor.configuration.security.InternalSecretLoader;

import javax.crypto.SecretKey;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
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

    @Autowired
    public WorkspaceSecurityImpl(
            RestClientProperties restClientProperties,
            @Value("${io.terrakube.registry.domain}") String registryDomain,
            @Value("${io.terrakube.api.url}") String apiUrl,
            InternalSecretLoader secretLoader) {
        this.clientProperties = restClientProperties;
        this.registryDomain = registryDomain;
        this.apiUrl = apiUrl;
        this.internalSecret = secretLoader.getInternalSecret();
    }

    public WorkspaceSecurityImpl(RestClientProperties restClientProperties, String registryDomain, String apiUrl, String internalSecret) {
        this(restClientProperties, registryDomain, apiUrl, new InternalSecretLoader(internalSecret, null));
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

        String token = generateAccessToken(60, organizationId, workspaceId, jobId, stepId);
        String credentialFileContent = String.format(CREDENTIALS_CONTENT, registryDomain, token);
        String credentialFileContent2 = "";
        try {
            credentialFileContent2 = String.format(CREDENTIALS_CONTENT, new URL(apiUrl).getHost(), token);
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

            }
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }
}
