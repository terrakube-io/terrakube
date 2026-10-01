package io.terrakube.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Base64;

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

    // log-read-url values issued before the URL-safe Base64 change are Base-36 BigIntegers and must
    // still decrypt while a Terraform CLI run spans the upgrade.
    @Test
    void decryptsLegacyBase36Values() {
        int checked = 0;
        for (int i = 0; i < 1_000; i++) {
            String value = String.valueOf(i);
            String[] parts = encryptionService.encrypt(value).split("/");
            byte[] iv = Base64.getUrlDecoder().decode(parts[0]);
            byte[] encryptedBytes = Base64.getUrlDecoder().decode(parts[1]);
            // Values the old encoding truncated (leading 0x00/0xFF byte) never decrypted, so skip them.
            if (!Arrays.equals(new BigInteger(iv).toByteArray(), iv)
                    || !Arrays.equals(new BigInteger(encryptedBytes).toByteArray(), encryptedBytes)) {
                continue;
            }
            String legacy = new BigInteger(iv).toString(36) + "/" + new BigInteger(encryptedBytes).toString(36);
            Assertions.assertEquals(value, encryptionService.decrypt(legacy));
            checked++;
        }
        Assertions.assertTrue(checked > 900, "too few legacy values checked: " + checked);
    }
}
