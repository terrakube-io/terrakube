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
}
