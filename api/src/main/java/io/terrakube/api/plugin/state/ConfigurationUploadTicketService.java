package io.terrakube.api.plugin.state;

import io.jsonwebtoken.io.Decoders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Slf4j
@Service
public class ConfigurationUploadTicketService {

    public enum TicketValidationResult {
        VALID,
        EXPIRED,
        INVALID
    }

    private final String internalSecret;
    private final int uploadTtlSeconds;

    public ConfigurationUploadTicketService(
            @Value("${io.terrakube.token.internal:}") String internalSecret,
            @Value("${io.terrakube.configuration.upload-ttl-seconds:3600}") int uploadTtlSeconds) {
        if (internalSecret == null || internalSecret.isBlank()) {
            this.internalSecret = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("default-internal-secret-for-config-upload-tickets!".getBytes(StandardCharsets.UTF_8));
        } else {
            this.internalSecret = internalSecret;
        }
        this.uploadTtlSeconds = uploadTtlSeconds;
    }

    public String generateTicket(String contentId) {
        long expTimestamp = Instant.now().getEpochSecond() + uploadTtlSeconds;
        String canonical = contentId + "/" + expTimestamp;
        byte[] signature = sign(canonical);
        return expTimestamp + "." + HexFormat.of().formatHex(signature);
    }

    public TicketValidationResult validateTicket(String ticket, String contentId) {
        if (ticket == null || ticket.isBlank()) {
            return TicketValidationResult.INVALID;
        }

        int dotIdx = ticket.indexOf('.');
        if (dotIdx <= 0 || dotIdx == ticket.length() - 1) {
            log.warn("Malformed upload ticket: missing delimiter or signature");
            return TicketValidationResult.INVALID;
        }

        long expTimestamp;
        try {
            expTimestamp = Long.parseLong(ticket.substring(0, dotIdx));
        } catch (NumberFormatException e) {
            log.warn("Malformed upload ticket expiration timestamp: {}", ticket.substring(0, dotIdx));
            return TicketValidationResult.INVALID;
        }

        if (Instant.now().getEpochSecond() > expTimestamp) {
            log.warn("Expired upload ticket for content {} (exp: {})", contentId, expTimestamp);
            return TicketValidationResult.EXPIRED;
        }

        byte[] actualSig;
        try {
            actualSig = HexFormat.of().parseHex(ticket.substring(dotIdx + 1));
        } catch (Exception e) {
            log.warn("Malformed upload ticket signature hex");
            return TicketValidationResult.INVALID;
        }

        String canonical = contentId + "/" + expTimestamp;
        byte[] expectedSig = sign(canonical);

        if (!MessageDigest.isEqual(expectedSig, actualSig)) {
            log.warn("Invalid signature on upload ticket for content {}", contentId);
            return TicketValidationResult.INVALID;
        }

        return TicketValidationResult.VALID;
    }

    private byte[] sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] keyBytes = getSecretBytes();
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 signature for upload ticket", e);
        }
    }

    private byte[] getSecretBytes() {
        try {
            return Decoders.BASE64URL.decode(internalSecret);
        } catch (Exception e) {
            return internalSecret.getBytes(StandardCharsets.UTF_8);
        }
    }
}
