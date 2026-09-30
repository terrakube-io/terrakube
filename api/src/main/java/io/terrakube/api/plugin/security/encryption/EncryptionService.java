package io.terrakube.api.plugin.security.encryption;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Random;

@Service
@Slf4j
public class EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;  // Tag length in bits
    private static final int GCM_IV_LENGTH = 12;  // GCM IV length (recommended is 12 bytes)
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${io.terrakube.token.internal}")
    private String internalToken;

    /**
     * Encrypts the given value using AES encryption with the internal token as the key.
     *
     * @param value The plaintext string to encrypt
     * @return The encrypted string in Base64 format, with IV prepended
     */
    public String encrypt(String value) {
        try {
            // Generate SecretKeySpec using the internal token
            SecretKeySpec keySpec = new SecretKeySpec(generateHashFromToken(internalToken), "AES");

            // Generate a random Initialization Vector (IV)
            byte[] iv = generateIv();
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);

            // Configure the Cipher for encryption
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, parameterSpec);

            // Perform encryption
            byte[] encryptedBytes = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

            // Encode IV and encrypted data in URL-safe Base64 (no "/"), and return as "IV/EncryptedData".
            // A BigInteger round trip is not used because it drops leading 0x00/0xFF bytes.
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString(iv) + "/" + encoder.encodeToString(encryptedBytes);
        } catch (Exception e) {
            log.error("Error during AES encryption: {}", e.getMessage(), e);
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypts the given encrypted text (Base64 encoded) using AES with the internal token as the key.
     *
     * @param encryptedText The encrypted string in the format "IV:EncryptedData"
     * @return The decrypted plaintext string
     */
    public String decrypt(String encryptedText) {
        try {
            // Split the input into IV and cipher text
            String[] parts = encryptedText.split("/");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid encrypted string format. Expected 'IV:EncryptedData'");
            }
            try {
                return decryptAesGcm(Base64.getUrlDecoder().decode(parts[0]), Base64.getUrlDecoder().decode(parts[1]));
            } catch (Exception e) {
                // Fallback: values encrypted before the switch to URL-safe Base64 (e.g. a log-read-url held by a
                // Terraform CLI run during a rolling upgrade) are Base-36 encoded. Base-36 digits are valid
                // URL-safe Base64 too, so such a value usually decodes above and is rejected by the GCM tag check.
                try {
                    return decryptAesGcm(new BigInteger(parts[0], 36).toByteArray(), new BigInteger(parts[1], 36).toByteArray());
                } catch (Exception legacyException) {
                    e.addSuppressed(legacyException);
                    throw e;
                }
            }
        } catch (Exception e) {
            log.error("Error during AES decryption: {}", e.getMessage(), e);
            throw new RuntimeException("Decryption failed", e);
        }
    }

    private String decryptAesGcm(byte[] iv, byte[] encryptedBytes) throws Exception {
        // Create SecretKeySpec and IvParameterSpec for decryption
        SecretKeySpec keySpec = new SecretKeySpec(generateHashFromToken(internalToken), "AES");
        GCMParameterSpec ivSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);

        // Configure Cipher for decryption
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

        // Perform decryption and return the plaintext string
        return new String(cipher.doFinal(encryptedBytes), StandardCharsets.UTF_8);
    }

    /**
     * Generates a 256-bit hash from the provided token using SHA-256,
     * which will be used as the AES key.
     *
     * @param token The secret token used as input for the hash
     * @return A 256-bit hash byte array
     * @throws NoSuchAlgorithmException If SHA-256 algorithm is not available
     */
    private byte[] generateHashFromToken(String token) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generates a random 16-byte Initialization Vector (IV) for AES.
     *
     * @return A randomly generated IV byte array
     */
    private byte[] generateIv() {
        byte[] iv = new byte[GCM_IV_LENGTH]; // AES block size is 16 bytes
        this.secureRandom.nextBytes(iv);
        return iv;
    }
}