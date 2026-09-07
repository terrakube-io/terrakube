package io.terrakube.registry.plugin.storage.configuration;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorageAutoConfigurationTest {

    @Test
    void responseChecksumValidationMapsFlagToSdkMode() {
        assertEquals(ResponseChecksumValidation.WHEN_SUPPORTED,
                StorageAutoConfiguration.responseChecksumValidation(true));
        assertEquals(ResponseChecksumValidation.WHEN_REQUIRED,
                StorageAutoConfiguration.responseChecksumValidation(false));
    }
}
