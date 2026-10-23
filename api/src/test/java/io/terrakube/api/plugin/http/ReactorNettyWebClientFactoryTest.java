package io.terrakube.api.plugin.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactorNettyWebClientFactoryTest {

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/target", exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/redirect-large-headers", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.getResponseHeaders().add("Content-Security-Policy", "connect-src " + "x".repeat(10_000));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirect-oversized-headers", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.getResponseHeaders().add("Content-Security-Policy", "connect-src " + "x".repeat(40_000));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void defaultMaxHeaderSizeIs32KiB() {
        assertEquals(32 * 1024, ReactorNettyWebClientFactory.MAX_RESPONSE_HEADER_SIZE);
    }

    @Test
    void connectorInstantiationsSucceed() {
        assertNotNull(ReactorNettyWebClientFactory.connector());
        assertNotNull(ReactorNettyWebClientFactory.connector(Duration.ofSeconds(10)));
        assertNotNull(ReactorNettyWebClientFactory.redirectingConnector(Duration.ofSeconds(10)));
        assertNotNull(ReactorNettyWebClientFactory.compressedConnector(Duration.ofSeconds(10)));
    }

    @Test
    void redirectingConnectorFollowsRedirectWithHeadersAbove8KiB() {
        WebClient client = WebClient.builder()
                .clientConnector(ReactorNettyWebClientFactory.redirectingConnector(Duration.ofSeconds(10)))
                .build();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect-large-headers";
        String response = client.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(5));

        assertEquals("{\"status\":\"ok\"}", response);
    }

    @Test
    void redirectingConnectorRejectsHeadersExceedingConfiguredLimit() {
        WebClient client = WebClient.builder()
                .clientConnector(ReactorNettyWebClientFactory.redirectingConnector(Duration.ofSeconds(10)))
                .build();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect-oversized-headers";
        WebClientRequestException ex = assertThrows(WebClientRequestException.class, () ->
                client.get()
                        .uri(url)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(5))
        );

        assertTrue(ex.getMessage().contains("larger than") || ex.getMessage().contains("TooLongHttpHeaderException"));
    }
}
