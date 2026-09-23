package io.terrakube.api.plugin.token.login;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Set;

@Component
public class LoopbackRedirectUriValidator {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    public void validate(String redirectUri) {
        URI uri;
        try {
            uri = new URI(redirectUri);
        } catch (Exception e) {
            throw new BrokerBadRequestException("redirect_uri is not a valid URI");
        }
        if (!"http".equals(uri.getScheme())) {
            throw new BrokerBadRequestException("redirect_uri must use http on a loopback address");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new BrokerBadRequestException("redirect_uri has no host");
        }
        if (!LOOPBACK_HOSTS.contains(host)) {
            throw new BrokerBadRequestException("redirect_uri must target a loopback address");
        }
        int port = uri.getPort();
        if (port < TerraformLoginProperties.PORT_LOW || port > TerraformLoginProperties.PORT_HIGH) {
            throw new BrokerBadRequestException("redirect_uri port is outside the allowed range");
        }
        if (!"/login".equals(uri.getPath())) {
            throw new BrokerBadRequestException("redirect_uri path must be /login");
        }
        if (uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw new BrokerBadRequestException("redirect_uri must not contain a query, fragment, or userinfo");
        }
    }
}
