package io.terrakube.api.plugin.vcs;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
public class WebhookHttpClientConfig {

    // A fresh RestTemplate(new HttpComponentsClientHttpRequestFactory()) per call (the previous
    // behavior of WebhookServiceBase.makeApiRequest) opens a brand-new TCP+TLS connection for every
    // GitHub/GitLab/Azure DevOps API call, with no cap on how many can be open at once. This pools
    // and reuses connections instead, and caps concurrent connections per target host
    // (setDefaultMaxPerRoute) at the workspace-fanout concurrency (Task 3's
    // io.terrakube.webhook.workspace-fanout.concurrency) - that's the actual ceiling on how many of
    // these calls run at once for a single delivery, so the connection pool shouldn't allow more
    // concurrency than the caller can ever produce. setMaxTotal is looser (5x) to allow for
    // multiple different target hosts (self-hosted GitHub Enterprise, GitLab, Azure DevOps, and any
    // number of distinct repos) without being unbounded.
    @Bean("webhookRestTemplate")
    public RestTemplate webhookRestTemplate(
            @Value("${io.terrakube.webhook.dispatch.http.connectTimeoutSeconds:5}") long connectTimeoutSeconds,
            @Value("${io.terrakube.webhook.dispatch.http.responseTimeoutSeconds:30}") long responseTimeoutSeconds,
            @Value("${io.terrakube.webhook.dispatch.http.connectionRequestTimeoutSeconds:2}") long connectionRequestTimeoutSeconds,
            @Value("${io.terrakube.webhook.workspace-fanout.concurrency:4}") int maxConnectionsPerRoute) {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(maxConnectionsPerRoute * 5);
        connectionManager.setDefaultMaxPerRoute(maxConnectionsPerRoute);
        connectionManager.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(connectTimeoutSeconds))
                .build());

        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofSeconds(responseTimeoutSeconds))
                .setConnectionRequestTimeout(Timeout.ofSeconds(connectionRequestTimeoutSeconds))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .build();

        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }

    @Bean
    public Counter webhookTimeoutCounter(MeterRegistry meterRegistry) {
        return Counter.builder("webhook.http.timeout.count")
                .description("GitHub/GitLab/Azure DevOps API calls that failed with a connection, response, or "
                        + "connection-pool-acquisition timeout (or any other low-level I/O failure Spring wraps "
                        + "the same way)")
                .register(meterRegistry);
    }
}
