package io.terrakube.api.plugin.routing;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Public addresses of the API for split-domain and single-domain deployments.
 *
 * <p>Single-domain mode is on exactly when {@code io.terrakube.public-url} is set. An explicitly
 * set {@code io.terrakube.hostname} still wins for generated API URLs, so existing installations
 * and dedicated API hostnames keep their URLs. That is the migration path: with both set, discovery
 * advertises the {@code /api} paths while state, upload, log and webhook URLs stay on the old API
 * host until TerrakubeHostname is removed.
 */
@Slf4j
@Component
public class PublicUrls {

    private final String publicUrl;
    private final String hostname;

    public PublicUrls(@Value("${io.terrakube.public-url:}") String publicUrl,
                      @Value("${io.terrakube.hostname:}") String hostname) {
        this.publicUrl = validatePublicUrl(publicUrl);
        this.hostname = hostname == null ? "" : hostname.trim();
        if (this.publicUrl.isEmpty() && this.hostname.isEmpty()) {
            throw new IllegalStateException(
                    "Set TerrakubeHostname (split-domain) or TerrakubePublicUrl (single-domain)");
        }
        if (!this.publicUrl.isEmpty() && !this.hostname.isEmpty()) {
            log.warn("Both TerrakubePublicUrl ({}) and TerrakubeHostname ({}) are set: discovery uses {}/api paths, "
                    + "but generated state, upload, log and webhook URLs still point at https://{}. "
                    + "Unset TerrakubeHostname once clients have moved to the shared host.",
                    this.publicUrl, this.hostname, this.publicUrl, this.hostname);
        }
    }

    public boolean isSingleDomain() {
        return !publicUrl.isEmpty();
    }

    /** The configured public URL, or an empty string in split-domain mode. */
    public String publicUrl() {
        return publicUrl;
    }

    /** Host (and port, if any) of the public URL, or an empty string in split-domain mode. */
    public String publicHost() {
        return publicUrl.isEmpty() ? "" : URI.create(publicUrl).getRawAuthority();
    }

    /**
     * Base for absolute API URLs handed to clients. Append a legacy path such as
     * {@code /tfstate/v1/...}; in single-domain mode the canonical {@code /api} prefix is included.
     */
    public String apiBaseUrl() {
        if (!hostname.isEmpty()) {
            return "https://" + hostname;
        }
        return publicUrl + "/api";
    }

    /**
     * Default host for the dynamic-credential token issuer when DynamicCredentialHostname is unset:
     * an explicitly set hostname first (so migrating installations keep their issuer), then the
     * public URL's host.
     */
    public String issuerHost() {
        return hostname.isEmpty() ? publicHost() : hostname;
    }

    /** Prefix for relative API paths handed to clients: {@code /api} in single-domain mode. */
    public String apiPathPrefix() {
        return isSingleDomain() ? "/api" : "";
    }

    static String validatePublicUrl(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String url = value.trim();
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw invalid(url);
        }
        boolean httpScheme = "https".equals(uri.getScheme()) || "http".equals(uri.getScheme());
        boolean noPath = uri.getRawPath() == null || uri.getRawPath().isEmpty();
        if (!httpScheme || uri.getHost() == null || !noPath || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw invalid(url);
        }
        return url;
    }

    private static IllegalStateException invalid(String url) {
        return new IllegalStateException("TerrakubePublicUrl must be an absolute http(s) URL with no path, "
                + "query or trailing slash, e.g. https://terrakube.example.com (got: " + url + ")");
    }
}
