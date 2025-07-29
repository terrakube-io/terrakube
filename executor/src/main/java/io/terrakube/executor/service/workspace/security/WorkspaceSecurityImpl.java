package io.terrakube.executor.service.workspace.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import io.terrakube.client.spring.autoconfigure.RestClientProperties;

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
    private static final String SUBJECT = "TerrakubeInternal (TOKEN)";
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

    public WorkspaceSecurityImpl(RestClientProperties restClientProperties, @Value("${io.terrakube.registry.domain}") String registryDomain, @Value("${io.terrakube.api.url}") String apiUrl,  @Value("${io.terrakube.client.secretKey}") String internalSecret) {
        this.clientProperties = restClientProperties;
        this.registryDomain = registryDomain;
        this.apiUrl = apiUrl;
        this.internalSecret = internalSecret;
    }

    @Override
    public String generateAccessToken() {
        log.error("Generate Dex Authentication Private Token");
        String newToken = "";

        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(this.internalSecret));

         return Jwts.builder()
                .setHeaderParam("typ", "JWT")
                .setIssuer(WorkspaceSecurityImpl.ISSUER)
                .setSubject(WorkspaceSecurityImpl.SUBJECT)
                .setAudience(WorkspaceSecurityImpl.ISSUER)
                .claim("email", WorkspaceSecurityImpl.EMAIL)
                .claim("email_verified", true)
                .claim("name", WorkspaceSecurityImpl.NAME)
                .setIssuedAt(Date.from(Instant.now()))
                .setExpiration(Date.from(Instant.now().plus(30, ChronoUnit.DAYS)))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();

    }

    @Override
    public String generateAccessToken(int minutes) {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(this.internalSecret));

        String token = Jwts.builder()
                .setHeaderParam("typ", "JWT")
                .setIssuer(WorkspaceSecurityImpl.ISSUER)
                .setSubject(WorkspaceSecurityImpl.SUBJECT)
                .setAudience(WorkspaceSecurityImpl.ISSUER)
                .claim("email", WorkspaceSecurityImpl.EMAIL)
                .claim("email_verified", true)
                .claim("name", WorkspaceSecurityImpl.NAME)
                .setIssuedAt(Date.from(Instant.now()))
                .setExpiration(Date.from(Instant.now().plus(minutes, ChronoUnit.MINUTES)))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
        return token;
    }

    @Override
    public void addTerraformCredentials() {

        String token = generateAccessToken();
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
