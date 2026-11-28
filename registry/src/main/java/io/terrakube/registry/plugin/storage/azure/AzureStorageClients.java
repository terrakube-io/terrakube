package io.terrakube.registry.plugin.storage.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * Builds the Azure Blob Storage client for the configured credentials.
 *
 * <p>When a storage account key is configured it is used as before. When it is absent or blank,
 * the client authenticates with Microsoft Entra ID through {@code DefaultAzureCredential}, so a
 * deployment on AKS can use Workload Identity (or a managed identity, or any other
 * {@code DefaultAzureCredential} source) without a long-lived account key.
 */
@Slf4j
public final class AzureStorageClients {

    private AzureStorageClients() {
    }

    public static BlobServiceClient blobServiceClient(String accountName, String accountKey) {
        return blobServiceClient(accountName, accountKey, () -> new DefaultAzureCredentialBuilder().build());
    }

    public static boolean usesEntraId(String accountKey) {
        return accountKey == null || accountKey.isBlank();
    }

    static BlobServiceClient blobServiceClient(String accountName, String accountKey, Supplier<TokenCredential> entraIdCredential) {
        if (accountName == null || accountName.isBlank()) {
            throw new IllegalStateException("The Azure storage account name is not configured (AzureAccountName / AzureAccountKey)");
        }

        if (usesEntraId(accountKey)) {
            log.info("No Azure storage account key configured, using Microsoft Entra ID (DefaultAzureCredential) for storage account {}", accountName);
            return new BlobServiceClientBuilder()
                    .endpoint(String.format("https://%s.blob.core.windows.net", accountName))
                    .credential(entraIdCredential.get())
                    .buildClient();
        }

        log.info("Using the configured account key for Azure storage account {}", accountName);
        return new BlobServiceClientBuilder()
                .connectionString(String.format("DefaultEndpointsProtocol=https;AccountName=%s;AccountKey=%s;EndpointSuffix=core.windows.net",
                        accountName, accountKey))
                .buildClient();
    }
}
