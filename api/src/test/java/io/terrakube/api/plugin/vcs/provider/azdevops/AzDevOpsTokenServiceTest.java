package io.terrakube.api.plugin.vcs.provider.azdevops;

import com.sun.net.httpserver.HttpServer;
import io.terrakube.api.plugin.vcs.provider.exception.TokenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AzDevOpsTokenServiceTest {

    private HttpServer server;
    private final List<List<String>> receivedContentTypes = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth2/token", exchange -> {
            List<String> contentTypes = exchange.getRequestHeaders().get("Content-Type");
            receivedContentTypes.add(contentTypes == null ? List.of() : List.copyOf(contentTypes));
            byte[] body = "{\"access_token\":\"az-test-token\",\"token_type\":\"bearer\",\"expires_in\":3600,\"refresh_token\":\"az-test-refresh\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void repeatedTokenRequestsMustNotAccumulateContentTypeHeaders() throws TokenException {
        AzDevOpsTokenService service = new AzDevOpsTokenService();
        ReflectionTestUtils.setField(service, "hostname", "localhost");
        ReflectionTestUtils.setField(service, "webClientBuilder", WebClient.builder());

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();

        for (int i = 0; i < 5; i++) {
            AzDevOpsToken token = service.getAccessToken("vcs-az", "secret", "temp-code", null, endpoint);
            assertNotNull(token);
            assertEquals("az-test-token", token.getAccess_token());
        }

        assertEquals(5, receivedContentTypes.size(), "expected 5 requests to mock server");
        for (int i = 0; i < receivedContentTypes.size(); i++) {
            List<String> contentTypes = receivedContentTypes.get(i);
            assertEquals(1, contentTypes.size(),
                    "request " + (i + 1) + " sent " + contentTypes.size() + " Content-Type values: " + contentTypes);
            assertTrue(contentTypes.get(0).contains("application/x-www-form-urlencoded"),
                    "unexpected Content-Type: " + contentTypes.get(0));
        }
    }
}
