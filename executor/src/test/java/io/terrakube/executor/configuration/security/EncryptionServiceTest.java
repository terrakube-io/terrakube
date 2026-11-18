package io.terrakube.executor.configuration.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EncryptionServiceTest {

    @Test
    void encryptAndDecryptRoundTrip() {
        InternalSecretLoader secretLoader = new InternalSecretLoader("test-secret-key-12345", null);
        EncryptionService encryptionService = new EncryptionService(secretLoader);

        String sample = "{\"type\":\"terraformPlan\",\"branch\":\"main\",\"workspaceId\":\"ws-1\"}";
        String encrypted = encryptionService.encrypt(sample);

        assertNotNull(encrypted);
        assertTrue(encrypted.contains("/"));
        assertNotEquals(sample, encrypted);

        String decrypted = encryptionService.decrypt(encrypted);
        assertEquals(sample, decrypted);
    }

    @Test
    void decryptsMultipleValuesConsistently() {
        InternalSecretLoader secretLoader = new InternalSecretLoader("test-secret-key-67890", null);
        EncryptionService encryptionService = new EncryptionService(secretLoader);

        for (int i = 0; i < 500; i++) {
            String value = "job-data-" + i;
            String encrypted = encryptionService.encrypt(value);
            assertEquals(value, encryptionService.decrypt(encrypted));
        }
    }

    @Test
    void decryptionFailsWithWrongSecret() {
        InternalSecretLoader secretLoader1 = new InternalSecretLoader("secret-one", null);
        InternalSecretLoader secretLoader2 = new InternalSecretLoader("secret-two", null);

        EncryptionService service1 = new EncryptionService(secretLoader1);
        EncryptionService service2 = new EncryptionService(secretLoader2);

        String encrypted = service1.encrypt("sensitive-data");
        assertThrows(RuntimeException.class, () -> service2.decrypt(encrypted));
    }

    @Test
    void failsWhenSecretIsMissingOrBlank() {
        InternalSecretLoader emptyLoader = new InternalSecretLoader("", null);
        EncryptionService service = new EncryptionService(emptyLoader);

        assertThrows(IllegalStateException.class, () -> service.encrypt("data"));
        assertThrows(IllegalStateException.class, () -> service.decrypt("iv/data"));
    }

    @Test
    void failsOnInvalidEncryptedFormat() {
        InternalSecretLoader secretLoader = new InternalSecretLoader("valid-secret", null);
        EncryptionService service = new EncryptionService(secretLoader);

        assertThrows(RuntimeException.class, () -> service.decrypt("invalid-string-without-slash"));
        assertThrows(RuntimeException.class, () -> service.decrypt("too/many/parts/here"));
    }

    public static final String FIXED_SECRET = "terrakube-drift-check-secret-token-2026";
    public static final String FIXED_CIPHERTEXT = "AQIDBAUGBwgJCgsM/xD-_1cOC-h4xYoiVSkzL0ijiZY9rRXgIj41ilS2HhKMeq5xCdNAVpJ9aaoWpehvRN8v_ecJd2rQvpImbz6vBpbsXJontWQ";
    public static final String EXPECTED_PLAINTEXT = "{\"jobId\":\"fixed-12345\",\"workspaceId\":\"fixed-ws-67890\"}";

    @Test
    void decryptsFixedTestVector() {
        InternalSecretLoader secretLoader = new InternalSecretLoader(FIXED_SECRET, null);
        EncryptionService service = new EncryptionService(secretLoader);
        assertEquals(EXPECTED_PLAINTEXT, service.decrypt(FIXED_CIPHERTEXT),
                "Must decrypt the fixed test vector identically across api and executor");
    }
}
