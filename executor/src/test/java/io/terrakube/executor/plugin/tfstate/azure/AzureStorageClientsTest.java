package io.terrakube.executor.plugin.tfstate.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.storage.blob.BlobServiceClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class AzureStorageClientsTest {

    private static final String ACCOUNT_KEY = "dGVycmFrdWJlLXRlc3QtYWNjb3VudC1rZXk=";

    @Test
    void blankAccountKeyUsesEntraId() {
        assertTrue(AzureStorageClients.usesEntraId(null));
        assertTrue(AzureStorageClients.usesEntraId(""));
        assertTrue(AzureStorageClients.usesEntraId("  "));
        assertFalse(AzureStorageClients.usesEntraId(ACCOUNT_KEY));
    }

    @Test
    void clientWithoutAnAccountKeyAuthenticatesWithEntraId() {
        CountingCredential credential = new CountingCredential();

        BlobServiceClient client = AzureStorageClients.blobServiceClient("terrakubestorage", "", credential);

        assertEquals(1, credential.requested.get());
        assertEquals("https://terrakubestorage.blob.core.windows.net", client.getAccountUrl());
        assertEquals("terrakubestorage", client.getAccountName());
    }

    @Test
    void configuredAccountKeyIsUsedInsteadOfEntraId() {
        BlobServiceClient client = AzureStorageClients.blobServiceClient("terrakubestorage", ACCOUNT_KEY, () -> {
            throw new AssertionError("Entra ID credentials must not be created when an account key is configured");
        });

        assertEquals("https://terrakubestorage.blob.core.windows.net", client.getAccountUrl());
        assertEquals("terrakubestorage", client.getAccountName());
    }

    @Test
    void missingAccountNameFailsWithAClearMessage() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AzureStorageClients.blobServiceClient(" ", "", new CountingCredential()));

        assertTrue(e.getMessage().contains("storage account name is not configured"), e.getMessage());
    }

    private static final class CountingCredential implements Supplier<TokenCredential> {
        private final AtomicInteger requested = new AtomicInteger();

        @Override
        public TokenCredential get() {
            requested.incrementAndGet();
            return request -> Mono.error(new IllegalStateException("no token in unit tests"));
        }
    }
}
