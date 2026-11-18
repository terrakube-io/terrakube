package io.terrakube.executor.configuration.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
@Slf4j
public class EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private final SecureRandom secureRandom = new SecureRandom();

    private final InternalSecretLoader secretLoader;

    public EncryptionService(InternalSecretLoader secretLoader) {
        this.secretLoader = secretLoader;
    }

    public String decrypt(String encryptedText) {
        String internalSecret = secretLoader.getInternalSecret();
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new IllegalStateException("Internal secret is not configured or is empty");
        }
        try {
            String[] parts = encryptedText.split("/");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid encrypted string format. Expected 'IV/EncryptedData'");
            }
            return decryptAesGcm(internalSecret, Base64.getUrlDecoder().decode(parts[0]), Base64.getUrlDecoder().decode(parts[1]));
        } catch (Exception e) {
            log.error("Error during AES decryption: {}", e.getMessage());
            throw new RuntimeException("Decryption failed", e);
        }
    }

    public String encrypt(String value) {
        String internalSecret = secretLoader.getInternalSecret();
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new IllegalStateException("Internal secret is not configured or is empty");
        }
        try {
            SecretKeySpec keySpec = new SecretKeySpec(generateHashFromToken(internalSecret), "AES");
            byte[] iv = generateIv();
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, parameterSpec);
            byte[] encryptedBytes = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString(iv) + "/" + encoder.encodeToString(encryptedBytes);
        } catch (Exception e) {
            log.error("Error during AES encryption: {}", e.getMessage(), e);
            throw new RuntimeException("Encryption failed", e);
        }
    }

    private String decryptAesGcm(String internalSecret, byte[] iv, byte[] encryptedBytes) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(generateHashFromToken(internalSecret), "AES");
        GCMParameterSpec ivSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        return new String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8);
    }

    private byte[] generateHashFromToken(String token) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(token.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] generateIv() {
        byte[] iv = new byte[GCM_IV_LENGTH];
        this.secureRandom.nextBytes(iv);
        return iv;
    }
}
