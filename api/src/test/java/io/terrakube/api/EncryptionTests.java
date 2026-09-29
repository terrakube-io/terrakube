package io.terrakube.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

public class EncryptionTests extends ServerApplicationTests {

    @Test
    void encryptSampleString() throws IOException {
        String encryptedValue = encryptionService.encrypt("1");
        System.out.println(encryptedValue);
        Assertions.assertEquals("1", encryptionService.decrypt(encryptedValue));
    }

    // A random IV or ciphertext starting with 0x00 or 0xFF used to lose that byte in the old
    // BigInteger encoding, so about 1 value in 128 could not be decrypted.
    @Test
    void decryptsEveryEncryptedValue() {
        for (int i = 0; i < 10_000; i++) {
            String value = String.valueOf(i);
            Assertions.assertEquals(value, encryptionService.decrypt(encryptionService.encrypt(value)));
        }
    }
}
