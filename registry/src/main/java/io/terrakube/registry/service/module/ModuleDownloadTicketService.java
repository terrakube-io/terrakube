package io.terrakube.registry.service.module;

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
public class ModuleDownloadTicketService {

    private final String internalSecret;
    private final int ticketTtlSeconds;

    public ModuleDownloadTicketService(
            @Value("${io.terrakube.token.internal:}") String internalSecret,
            @Value("${io.terrakube.registry.ticket-ttl-seconds:300}") int ticketTtlSeconds) {
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new IllegalArgumentException("Missing required configuration property: io.terrakube.token.internal");
        }
        this.internalSecret = internalSecret;
        this.ticketTtlSeconds = ticketTtlSeconds;
    }

    public String generateTicket(String organization, String module, String provider, String version) {
        long expTimestamp = Instant.now().getEpochSecond() + ticketTtlSeconds;
        String canonical = buildCanonicalString(organization, module, provider, version, expTimestamp);
        byte[] signature = sign(canonical);
        return expTimestamp + "." + HexFormat.of().formatHex(signature);
    }

    public boolean validateTicket(String ticket, String organization, String module, String provider, String version) {
        if (ticket == null || ticket.isBlank()) {
            return false;
        }

        int dotIdx = ticket.indexOf('.');
        if (dotIdx <= 0 || dotIdx == ticket.length() - 1) {
            log.warn("Malformed download ticket: missing delimiter or signature");
            return false;
        }

        long expTimestamp;
        try {
            expTimestamp = Long.parseLong(ticket.substring(0, dotIdx));
        } catch (NumberFormatException e) {
            log.warn("Malformed download ticket expiration timestamp: {}", ticket.substring(0, dotIdx));
            return false;
        }

        if (Instant.now().getEpochSecond() > expTimestamp) {
            log.warn("Expired download ticket for {}/{}/{}/{} (exp: {})", organization, module, provider, version, expTimestamp);
            return false;
        }

        byte[] actualSig;
        try {
            actualSig = HexFormat.of().parseHex(ticket.substring(dotIdx + 1));
        } catch (Exception e) {
            log.warn("Malformed download ticket signature hex");
            return false;
        }

        String canonical = buildCanonicalString(organization, module, provider, version, expTimestamp);
        byte[] expectedSig = sign(canonical);

        boolean matches = MessageDigest.isEqual(expectedSig, actualSig);
        if (!matches) {
            log.warn("Invalid signature on download ticket for {}/{}/{}/{}", organization, module, provider, version);
        }
        return matches;
    }

    private String buildCanonicalString(String organization, String module, String provider, String version, long expTimestamp) {
        return String.format("%s/%s/%s/%s/%d", organization, module, provider, version, expTimestamp);
    }

    private byte[] sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] keyBytes = getSecretBytes();
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 signature for module download ticket", e);
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
