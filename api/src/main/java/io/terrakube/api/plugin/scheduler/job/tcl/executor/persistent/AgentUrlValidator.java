package io.terrakube.api.plugin.scheduler.job.tcl.executor.persistent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

@Slf4j
@Component
public class AgentUrlValidator {

    private final boolean blockPrivateNetworks;

    public AgentUrlValidator() {
        this(true);
    }

    @Autowired
    public AgentUrlValidator(
            @Value("${io.terrakube.agent.ssrf.blockPrivateNetworks:true}") boolean blockPrivateNetworks) {
        this.blockPrivateNetworks = blockPrivateNetworks;
    }

    public void validate(String channelLabel, String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw blocked(channelLabel, "destination URL cannot be null or empty");
        }

        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw blocked(channelLabel, "destination URL is not a valid URI");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw blocked(channelLabel, "destination URL must use http or https");
        }

        if (uri.getUserInfo() != null) {
            throw blocked(channelLabel, "destination URL must not contain embedded user credentials");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw blocked(channelLabel, "destination URL has no host");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            if (blockPrivateNetworks) {
                throw blocked(channelLabel, "destination host could not be resolved");
            } else {
                log.debug("Agent host {} could not be resolved at validation time, proceeding for internal resolution", host);
                return;
            }
        }

        for (InetAddress address : addresses) {
            if (isDisallowed(address, blockPrivateNetworks)) {
                log.warn("Blocked SSRF attempt in {} to prohibited address: {}", channelLabel, address.getHostAddress());
                throw blocked(channelLabel, "destination resolves to a prohibited address (" + address.getHostAddress() + ")");
            }
        }
    }

    private static boolean isDisallowed(InetAddress address, boolean blockPrivate) {
        // ALWAYS block cloud metadata and link-local addresses
        if (address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            // 169.254.0.0/16 - Link-local / Cloud Instance Metadata (e.g. AWS/GCP/Azure 169.254.169.254)
            if (first == 169 && second == 254) {
                return true;
            }
        }

        if (!blockPrivate) {
            return false;
        }

        // When blockPrivate is true, also block loopback and private networks
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isAnyLocalAddress()) {
            return true;
        }

        if (address instanceof Inet4Address && bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            // 100.64.0.0/10 - Carrier-grade NAT shared address space
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
        } else if (bytes.length == 16) {
            // fc00::/7 - IPv6 unique local addresses
            int first = bytes[0] & 0xFF;
            if ((first & 0xFE) == 0xFC) {
                return true;
            }
        }

        return false;
    }

    private static IllegalArgumentException blocked(String channelLabel, String reason) {
        return new IllegalArgumentException(channelLabel + " URL blocked: " + reason);
    }
}
