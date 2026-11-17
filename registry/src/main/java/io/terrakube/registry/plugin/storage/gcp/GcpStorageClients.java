package io.terrakube.registry.plugin.storage.gcp;

import com.google.auth.ServiceAccountSigner;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Builds the Google Cloud Storage client for the configured credentials.
 *
 * <p>When a Base64 encoded service account key is configured it is used as before. When it is
 * absent or blank, Application Default Credentials are used instead, so a deployment on GKE can
 * authenticate through Workload Identity (or any other ADC source) without a long-lived key.
 * Credentials that cannot be loaded fail startup with a message that says why, instead of leaving
 * the storage service unset.
 */
@Slf4j
public final class GcpStorageClients {

    @FunctionalInterface
    interface ApplicationDefaultCredentialsLoader {
        GoogleCredentials load() throws IOException;
    }

    private GcpStorageClients() {
    }

    public static GoogleCredentials credentials(String base64Credentials) {
        return credentials(base64Credentials, GoogleCredentials::getApplicationDefault);
    }

    public static Storage storage(GoogleCredentials credentials, String projectId) {
        StorageOptions.Builder options = StorageOptions.newBuilder().setCredentials(credentials);
        // Without a project id the client resolves it from the environment (ADC, metadata server).
        if (projectId != null && !projectId.isBlank()) {
            options.setProjectId(projectId);
        }
        return options.build().getService();
    }

    /**
     * V4-signed URLs are signed with the credentials' service account. A service account key signs
     * locally; metadata server and impersonated credentials sign through the IAM {@code signBlob}
     * API. Credentials that cannot sign at all, such as user credentials or workload identity
     * federation without service account impersonation, are refused at startup instead of failing
     * on the first module download.
     *
     * <p>IAM can still refuse to sign at request time, for example for a GKE Workload Identity
     * principal that is not bound to a Google service account, or when the service account lacks
     * {@code iam.serviceAccounts.signBlob} on itself. {@link GcpStorageServiceImpl} reports that.
     */
    public static void requireUrlSigning(GoogleCredentials credentials) {
        if (!(credentials instanceof ServiceAccountSigner)) {
            throw new IllegalStateException("GcpPresignedRedirectEnabled is set, but the GCP credentials ("
                    + credentials.getClass().getSimpleName() + ") cannot sign URLs. Use a Google service account, "
                    + "either through a key or through Application Default Credentials bound to one, or disable "
                    + "GcpPresignedRedirectEnabled");
        }
    }

    static GoogleCredentials credentials(String base64Credentials, ApplicationDefaultCredentialsLoader applicationDefault) {
        if (base64Credentials == null || base64Credentials.isBlank()) {
            log.info("No GCP credentials configured, using Application Default Credentials");
            try {
                return applicationDefault.load();
            } catch (IOException e) {
                throw new IllegalStateException("No GCP credentials are configured and Application Default Credentials "
                        + "are not available. Set the Base64 encoded service account key, or run with Application "
                        + "Default Credentials (for example GKE Workload Identity)", e);
            }
        }

        log.info("Using the configured GCP service account key ({} Base64 characters)", base64Credentials.length());
        try {
            return GoogleCredentials.fromStream(new ByteArrayInputStream(Base64.getDecoder().decode(base64Credentials)));
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("The configured GCP credentials could not be loaded: " + e.getMessage(), e);
        }
    }
}
