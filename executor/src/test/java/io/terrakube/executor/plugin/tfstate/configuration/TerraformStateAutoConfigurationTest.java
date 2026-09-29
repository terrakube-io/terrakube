package io.terrakube.executor.plugin.tfstate.configuration;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerraformStateAutoConfigurationTest {

    @Test
    void responseChecksumValidationMapsFlagToSdkMode() {
        assertEquals(ResponseChecksumValidation.WHEN_SUPPORTED,
                TerraformStateAutoConfiguration.responseChecksumValidation(true));
        assertEquals(ResponseChecksumValidation.WHEN_REQUIRED,
                TerraformStateAutoConfiguration.responseChecksumValidation(false));
    }

    @Test
    void azureBlobServiceClientUsesEntraIdWhenAccessKeyIsBlank() {
        assertEquals("https://acct.blob.core.windows.net",
                TerraformStateAutoConfiguration.azureBlobServiceClient("acct", "").getAccountUrl());
        assertEquals("https://acct.blob.core.windows.net",
                TerraformStateAutoConfiguration.azureBlobServiceClient("acct", null).getAccountUrl());
    }

    @Test
    void azureBlobServiceClientUsesSharedKeyWhenAccessKeyIsSet() {
        String key = java.util.Base64.getEncoder().encodeToString("test-key".getBytes());
        assertEquals("https://acct.blob.core.windows.net",
                TerraformStateAutoConfiguration.azureBlobServiceClient("acct", key).getAccountUrl());
    }
}
