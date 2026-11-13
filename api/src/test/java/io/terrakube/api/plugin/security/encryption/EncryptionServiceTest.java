package io.terrakube.api.plugin.security.encryption;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncryptionServiceTest {

    public static final String FIXED_SECRET = "terrakube-drift-check-secret-token-2026";
    public static final String FIXED_CIPHERTEXT = "AQIDBAUGBwgJCgsM/xD-_1cOC-h4xYoiVSkzL0ijiZY9rRXgIj41ilS2HhKMeq5xCdNAVpJ9aaoWpehvRN8v_ecJd2rQvpImbz6vBpbsXJontWQ";
    public static final String EXPECTED_PLAINTEXT = "{\"jobId\":\"fixed-12345\",\"workspaceId\":\"fixed-ws-67890\"}";

    @Test
    void encryptAndDecryptRoundTrip() {
        EncryptionService service = new EncryptionService();
        ReflectionTestUtils.setField(service, "internalToken", "test-internal-token-12345");

        String sample = "{\"jobId\":\"123\",\"branch\":\"main\",\"workspaceId\":\"ws-1\"}";
        String encrypted = service.encrypt(sample);

        assertNotNull(encrypted);
        assertTrue(encrypted.contains("/"));
        assertNotEquals(sample, encrypted);

        String decrypted = service.decrypt(encrypted);
        assertEquals(sample, decrypted);
    }

    @Test
    void decryptsFixedTestVector() {
        EncryptionService service = new EncryptionService();
        ReflectionTestUtils.setField(service, "internalToken", FIXED_SECRET);

        String decrypted = service.decrypt(FIXED_CIPHERTEXT);
        assertEquals(EXPECTED_PLAINTEXT, decrypted, "Must decrypt the fixed test vector identically across api and executor");
    }

    @Test
    void decryptionFailsWithWrongSecret() {
        EncryptionService service1 = new EncryptionService();
        ReflectionTestUtils.setField(service1, "internalToken", "secret-one");

        EncryptionService service2 = new EncryptionService();
        ReflectionTestUtils.setField(service2, "internalToken", "secret-two");

        String encrypted = service1.encrypt("confidential-payload");
        assertThrows(RuntimeException.class, () -> service2.decrypt(encrypted));
    }
}
