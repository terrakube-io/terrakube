package io.terrakube.api.plugin.webclient;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import javax.net.ssl.SSLException;

@Slf4j
@Configuration
@EnableConfigurationProperties({
        WebClientConfigProperties.class
})
public class WebClientConfig {
    @Bean
    public WebClient.Builder webClientBuilder(WebClientConfigProperties webClientConfigProperties) {
        log.info("Creating WebClient.Builder");

        HttpClient httpClient = HttpClient.create();
        if (webClientConfigProperties.isProxyEnabled()) {
            log.info("Using proxy: {}:{}", webClientConfigProperties.getProxyHost(), webClientConfigProperties.getProxyPort());

            httpClient = httpClient.proxy(proxySpec -> {
                ProxyProvider.Builder builder = proxySpec.type(ProxyProvider.Proxy.HTTP)
                        .host(webClientConfigProperties.getProxyHost())
                        .port(webClientConfigProperties.getProxyPort())
                        .username(webClientConfigProperties.getProxyUsername().isEmpty() ? null :
                                  webClientConfigProperties.getProxyUsername())
                        .password(webClientConfigProperties.getProxyPassword().isEmpty() ? null :
                                s -> webClientConfigProperties.getProxyPassword());
            });

            if (webClientConfigProperties.isProxyUseTls()) {
                log.info("Using TLS for proxy");
                try {
                    SslContext sslContext = SslContextBuilder.forClient().build();
                    httpClient = httpClient.secure(sslSpec -> sslSpec.sslContext(sslContext));
                } catch (SSLException e) {
                    log.error("Error creating SSL context: {}", e.getMessage());
                    throw new RuntimeException("Failed to configure SSL for proxy", e);
                }
            }
        }

        ReactorClientHttpConnector connector = new ReactorClientHttpConnector(httpClient);

        return WebClient.builder().clientConnector(connector);
    }

    @Bean
    public WebClient webClient(WebClient.Builder webClientBuilder) {
        return webClientBuilder.build(); // Build WebClient once and share it
    }
}
