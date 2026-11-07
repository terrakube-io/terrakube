package io.terrakube.api.plugin.routing;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Public addresses of the API for split-domain and single-domain deployments.
 *
 * <p>Single-domain mode is on exactly when {@code io.terrakube.public-url} is set. An explicitly
 * set {@code io.terrakube.hostname} still wins for generated API URLs, so existing installations
 * and dedicated API hostnames keep their URLs.
 */
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
     * Default dynamic-credential token issuer when DynamicCredentialHostname is unset: an explicitly
     * set hostname first, as {@code https://<hostname>} (so existing issuers never change), then the
     * public URL with its own scheme.
     */
    public String issuerBaseUrl() {
        return hostname.isEmpty() ? publicUrl : "https://" + hostname;
    }

    /** Dynamic-credential issuer: the explicit override when set, otherwise {@link #issuerBaseUrl()}. */
    public String dynamicCredentialIssuer(String override) {
        return override == null || override.isBlank() ? issuerBaseUrl() : issuerUrl(override);
    }

    /**
     * Issuer for an explicit DynamicCredentialHostname: a full {@code http(s)://} URL is used as is
     * (without a trailing slash), a bare host keeps the {@code https://} default.
     */
    public static String issuerUrl(String override) {
        String value = override.trim();
        if (value.contains("://")) {
            if (!value.startsWith("https://") && !value.startsWith("http://")) {
                throw new IllegalStateException("DynamicCredentialHostname must be a host or an http(s):// URL (got: " + value + ")");
            }
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        }
        return "https://" + value;
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
