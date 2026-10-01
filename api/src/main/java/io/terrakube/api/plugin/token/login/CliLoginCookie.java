package io.terrakube.api.plugin.token.login;

import io.jsonwebtoken.io.Decoders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

@Component
public class CliLoginCookie {

    public static final String COOKIE_NAME = "tk_cli_login";
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final byte[] HKDF_INFO = "terrakube-cli-login-cookie-v1".getBytes(StandardCharsets.UTF_8);

    private final byte[] key;

    public CliLoginCookie(@Value("${io.terrakube.token.pat}") String patSecretBase64) {
        byte[] ikm = Decoders.BASE64URL.decode(patSecretBase64);
        this.key = hkdfSha256(ikm, HKDF_INFO, 32);
    }

    public String sign(String sessionId) {
        return sessionId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(sessionId));
    }

    public Optional<String> verify(String cookieValue) {
        if (cookieValue == null) {
            return Optional.empty();
        }
        int dot = cookieValue.lastIndexOf('.');
        if (dot <= 0 || dot == cookieValue.length() - 1) {
            return Optional.empty();
        }
        String sid = cookieValue.substring(0, dot);
        String sig = cookieValue.substring(dot + 1);
        byte[] expected = Base64.getUrlEncoder().withoutPadding().encodeToString(mac(sid))
            .getBytes(StandardCharsets.US_ASCII);
        if (MessageDigest.isEqual(expected, sig.getBytes(StandardCharsets.US_ASCII))) {
            return Optional.of(sid);
        }
        return Optional.empty();
    }

    public ResponseCookie build(String sessionId) {
        return baseBuilder(sign(sessionId)).maxAge(TTL).build();
    }

    public ResponseCookie clear() {
        return baseBuilder("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder baseBuilder(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
            .httpOnly(true).secure(true).sameSite("Lax").path("/oauth");
    }

    private byte[] mac(String data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // RFC 5869 HKDF-SHA256 (extract + expand), single-block output (len <= 32)
    private static byte[] hkdfSha256(byte[] ikm, byte[] info, int len) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(new byte[32], "HmacSHA256")); // zero salt
            byte[] prk = m.doFinal(ikm);
            m.init(new SecretKeySpec(prk, "HmacSHA256"));
            m.update(info);
            m.update((byte) 0x01);
            byte[] t = m.doFinal();
            byte[] out = new byte[len];
            System.arraycopy(t, 0, out, 0, len);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
