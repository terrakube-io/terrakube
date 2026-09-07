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
}
