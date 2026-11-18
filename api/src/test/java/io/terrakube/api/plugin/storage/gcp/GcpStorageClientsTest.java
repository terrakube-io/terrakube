package io.terrakube.api.plugin.storage.gcp;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.Storage;
import org.apache.commons.codec.binary.Base64;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.*;

class GcpStorageClientsTest {

    private static final GoogleCredentials APPLICATION_DEFAULT = GoogleCredentials.create(new AccessToken("adc-token", null));

    @Test
    void blankCredentialsUseApplicationDefaultCredentials() {
        assertSame(APPLICATION_DEFAULT, GcpStorageClients.credentials(null, () -> APPLICATION_DEFAULT));
        assertSame(APPLICATION_DEFAULT, GcpStorageClients.credentials("", () -> APPLICATION_DEFAULT));
        assertSame(APPLICATION_DEFAULT, GcpStorageClients.credentials("  ", () -> APPLICATION_DEFAULT));
    }

    @Test
    void missingApplicationDefaultCredentialsFailWithAClearMessage() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> GcpStorageClients.credentials("", () -> {
                    throw new IOException("Your default credentials were not found");
                }));

        assertTrue(e.getMessage().contains("Application Default Credentials are not available"), e.getMessage());
        assertInstanceOf(IOException.class, e.getCause());
    }

    @Test
    void configuredKeyIsUsedInsteadOfApplicationDefaultCredentials() throws Exception {
        GoogleCredentials credentials = GcpStorageClients.credentials(serviceAccountKeyBase64(), () -> {
            throw new AssertionError("Application Default Credentials must not be loaded when a key is configured");
        });

        ServiceAccountCredentials serviceAccount = assertInstanceOf(ServiceAccountCredentials.class, credentials);
        assertEquals("terrakube@key-project.iam.gserviceaccount.com", serviceAccount.getClientEmail());
    }

    @Test
    void invalidKeyFailsInsteadOfLeavingTheStorageServiceUnset() {
        String notAKey = Base64.encodeBase64String("{\"type\": \"service_account\"}".getBytes(StandardCharsets.UTF_8));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> GcpStorageClients.credentials(notAKey, () -> APPLICATION_DEFAULT));

        assertTrue(e.getMessage().startsWith("The configured GCP credentials could not be loaded"), e.getMessage());
    }

    @Test
    void storageUsesTheConfiguredProjectId() {
        Storage storage = GcpStorageClients.storage(APPLICATION_DEFAULT, "configured-project");

        assertEquals("configured-project", storage.getOptions().getProjectId());
        assertSame(APPLICATION_DEFAULT, storage.getOptions().getCredentials());
    }

    @Test
    void storageResolvesTheProjectIdWhenNoneIsConfigured() throws Exception {
        GoogleCredentials key = GcpStorageClients.credentials(serviceAccountKeyBase64(), () -> APPLICATION_DEFAULT);

        assertEquals("key-project", GcpStorageClients.storage(key, "").getOptions().getProjectId());
        assertEquals("key-project", GcpStorageClients.storage(key, null).getOptions().getProjectId());
    }

    static String serviceAccountKeyBase64() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String privateKey = "-----BEGIN PRIVATE KEY-----\\n"
                + java.util.Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\\n-----END PRIVATE KEY-----\\n";
        String key = "{"
                + "\"type\": \"service_account\","
                + "\"project_id\": \"key-project\","
                + "\"private_key_id\": \"test-key-id\","
                + "\"private_key\": \"" + privateKey + "\","
                + "\"client_email\": \"terrakube@key-project.iam.gserviceaccount.com\","
                + "\"client_id\": \"123456789\","
                + "\"token_uri\": \"https://oauth2.googleapis.com/token\""
                + "}";
        return Base64.encodeBase64String(key.getBytes(StandardCharsets.UTF_8));
    }
}
