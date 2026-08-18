package io.terrakube.api.plugin.notification.sender;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Blocks the notification senders from being used as an SSRF pivot: destinationUrl is set by an
// authenticated workspace/org admin, but a careless or compromised admin account could still point
// it at internal infrastructure (a cloud metadata endpoint, an internal admin API, another pod in
// the cluster) and have the API server make an authenticated-context request to it on their
// behalf - and the ad-hoc pre-save test endpoint turns that into a free port-scanning oracle
// (its 200-vs-502 response leaks whether something is listening) unless this runs there too.
// Resolves the hostname and rejects anything that lands in a private/reserved/loopback/link-local
// range. Re-checked on every send (not just at config save time) so a DNS record that pointed
// somewhere public when the config was created can't quietly start resolving internally later -
// this narrows, but (being a resolve-then-connect check rather than a pinned-IP request) does not
// fully close, a same-millisecond DNS-rebinding race, which is an accepted tradeoff given the
// destination is set by an already-authenticated, RBAC-gated admin rather than an arbitrary caller.
//
// Blocking is opt-out (io.terrakube.notification.ssrf.blockPrivateNetworks=false)
// rather than hardcoded, because plenty of self-hosted Terrakube installs legitimately want to
// notify something on their own private network (an internal ChatOps relay, a webhook aggregator
// inside the same cluster) - default is blocked (secure by default), matching the multi-tenant
// SaaS-style trust model this feature is otherwise built around.
@Component
public class DestinationUrlValidator {

    private final boolean blockPrivateDestinations;

    public DestinationUrlValidator(
            @Value("${io.terrakube.notification.ssrf.blockPrivateNetworks:true}") boolean blockPrivateDestinations) {
        this.blockPrivateDestinations = blockPrivateDestinations;
    }

    void validate(String channelLabel, String rawUrl) {
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException | NullPointerException e) {
            throw blocked(channelLabel, "destination URL is not a valid URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw blocked(channelLabel, "destination URL must use http or https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw blocked(channelLabel, "destination URL has no host");
        }
        if (!blockPrivateDestinations) {
            return;
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw blocked(channelLabel, "destination host could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (isDisallowed(address)) {
                throw blocked(channelLabel, "destination resolves to a private/reserved address");
            }
        }
    }

    private static boolean isDisallowed(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            // 100.64.0.0/10 - carrier-grade NAT shared address space; not covered by any of the
            // isXxx() helpers above but still not a legitimate public destination.
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
        } else if (bytes.length == 16) {
            // fc00::/7 - IPv6 unique local addresses. isSiteLocalAddress() only recognizes the
            // older, deprecated fec0::/10 range, not this one.
            int first = bytes[0] & 0xFF;
            if ((first & 0xFE) == 0xFC) {
                return true;
            }
        }
        return false;
    }

    private static NotificationDeliveryException blocked(String channelLabel, String reason) {
        return new NotificationDeliveryException(channelLabel + " delivery blocked: " + reason, null, false);
    }
}
