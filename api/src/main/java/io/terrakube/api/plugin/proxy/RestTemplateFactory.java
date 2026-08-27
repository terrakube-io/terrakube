package io.terrakube.api.plugin.proxy;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

public final class RestTemplateFactory {

    private RestTemplateFactory() {
    }

    public static RestTemplate build(Duration connectTimeout, Duration readTimeout) {
        // Destination URLs are validated against private/loopback/link-local/reserved ranges
        // before every send (see DestinationUrlValidator), but that check only ever looks at the
        // configured URL's host - a 3xx response from an allowed public host would otherwise be
        // followed transparently (HttpURLConnection's instance-follow-redirects defaults to true),
        // letting an attacker-controlled or compromised destination redirect the request to an
        // internal address after validation has already passed. Disabling redirects here closes
        // that bypass; a webhook destination has no legitimate reason to redirect.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return new RestTemplate(factory);
    }

    public static RestTemplate build() {
        return build(Duration.ofSeconds(10), Duration.ofSeconds(30));
    }
}
