package io.terrakube.api.plugin.http;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Creates Reactor Netty clients with the API's standard outbound transport limits.
 * Callers retain ownership of request-specific policies such as overall deadlines,
 * authentication headers, codecs, and retries.
 */
public final class ReactorNettyWebClientFactory {

    public static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(30);
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    private ReactorNettyWebClientFactory() {
    }

    public static ReactorClientHttpConnector connector() {
        return connector(DEFAULT_RESPONSE_TIMEOUT);
    }

    public static ReactorClientHttpConnector connector(Duration responseTimeout) {
        return new ReactorClientHttpConnector(httpClient(responseTimeout));
    }

    public static ReactorClientHttpConnector redirectingConnector(Duration responseTimeout) {
        return new ReactorClientHttpConnector(httpClient(responseTimeout).followRedirect(true));
    }

    public static ReactorClientHttpConnector compressedConnector(Duration responseTimeout) {
        return new ReactorClientHttpConnector(httpClient(responseTimeout).compress(true));
    }

    private static HttpClient httpClient(Duration responseTimeout) {
        return HttpClient.create()
                .proxyWithSystemProperties()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .responseTimeout(responseTimeout)
                .doOnConnected(connection -> connection
                        .addHandlerLast(new ReadTimeoutHandler(responseTimeout.toMillis(), TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(responseTimeout.toMillis(), TimeUnit.MILLISECONDS)));
    }
}
