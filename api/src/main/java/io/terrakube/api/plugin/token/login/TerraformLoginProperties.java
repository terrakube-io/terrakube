package io.terrakube.api.plugin.token.login;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "io.terrakube.token.login")
public class TerraformLoginProperties {

    public static final String CLIENT_ID = "terraform-cli";
    public static final int PORT_LOW = 10000;
    public static final int PORT_HIGH = 10010;

    private boolean enabled = false;
    private int defaultDays = 30;
    private int maxDays = 365;
    private String apiUrl;
    private int cleanupIntervalMs = 300000;

    @PostConstruct
    public void normalize() {
        if (maxDays > 365) maxDays = 365;
        if (maxDays < 1) maxDays = 1;
        if (defaultDays < 1) defaultDays = 1;
        if (defaultDays > maxDays) defaultDays = maxDays;
        if (enabled && (apiUrl == null || apiUrl.isBlank())) {
            throw new IllegalStateException(
                "io.terrakube.token.login.enabled=true requires io.terrakube.token.login.api-url");
        }
        if (apiUrl != null && apiUrl.endsWith("/")) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        }
        if (enabled) {
            requireSecureApiUrl();
        }
    }

    // The broker sets a Secure session cookie and hands the CLI a bearer token; the whole flow
    // must run over TLS. Allow plain http only for a loopback host (local development).
    private void requireSecureApiUrl() {
        URI uri;
        try {
            uri = URI.create(apiUrl);
        } catch (RuntimeException e) {
            throw new IllegalStateException("io.terrakube.token.login.api-url is not a valid URL: " + apiUrl);
        }
        boolean loopback = uri.getHost() != null && LoopbackRedirectUriValidator.LOOPBACK_HOSTS.contains(uri.getHost());
        if (!"https".equals(uri.getScheme()) && !loopback) {
            throw new IllegalStateException(
                "io.terrakube.token.login.api-url must use https (got: " + apiUrl + ")");
        }
    }

    public String getCallbackUrl() {
        return apiUrl + "/oauth/callback";
    }
}
