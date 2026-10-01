package io.terrakube.api.plugin.token.login;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class PkceUtil {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private PkceUtil() {
    }

    public static String generateCodeVerifier() {
        byte[] bytes = new byte[48]; // 64 base64url chars
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    public static String codeChallengeS256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return B64.encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verifyS256(String verifier, String expectedChallenge) {
        if (verifier == null || expectedChallenge == null) {
            return false;
        }
        return MessageDigest.isEqual(
            codeChallengeS256(verifier).getBytes(StandardCharsets.US_ASCII),
            expectedChallenge.getBytes(StandardCharsets.US_ASCII));
    }
}
