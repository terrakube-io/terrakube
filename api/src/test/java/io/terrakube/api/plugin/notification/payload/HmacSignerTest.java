package io.terrakube.api.plugin.notification.payload;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSignerTest {

    @Test
    void producesStableHexEncodedSignatureForSameInput() {
        String signature1 = HmacSigner.sign("my-secret", "{\"a\":1}");
        String signature2 = HmacSigner.sign("my-secret", "{\"a\":1}");

        assertThat(signature1).isEqualTo(signature2);
        assertThat(signature1).matches("^[0-9a-f]{64}$");
    }

    @Test
    void differentSecretsProduceDifferentSignatures() {
        String signature1 = HmacSigner.sign("secret-one", "payload");
        String signature2 = HmacSigner.sign("secret-two", "payload");

        assertThat(signature1).isNotEqualTo(signature2);
    }
}
