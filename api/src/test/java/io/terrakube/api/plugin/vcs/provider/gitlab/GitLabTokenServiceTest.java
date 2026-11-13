
package io.terrakube.api.plugin.vcs.provider.gitlab;

import com.sun.net.httpserver.HttpServer;
import io.terrakube.api.plugin.vcs.provider.exception.TokenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GitLabTokenServiceTest {

    private HttpServer server;
    private final List<List<String>> receivedAcceptHeaders = new ArrayList<>();
    private final List<String> receivedRedirectUris = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        receivedAcceptHeaders.clear();
        receivedRedirectUris.clear();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth/token", exchange -> {
            List<String> acceptHeaders = exchange.getRequestHeaders().get("Accept");
            receivedAcceptHeaders.add(
                    acceptHeaders == null ? List.of() : List.copyOf(acceptHeaders));

            String query = exchange.getRequestURI().getRawQuery();
            if (query != null) {
                for (String parameter : query.split("&")) {
                    String[] parts = parameter.split("=", 2);
                    if (parts.length == 2 && parts[0].equals("redirect_uri")) {
                        receivedRedirectUris.add(
                                URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
                    }
                }
            }

            byte[] body = "{\"access_token\":\"test-token\",\"token_type\":\"bearer\",\"expires_in\":3600,\"refresh_token\":\"test-refresh\",\"created_at\":12345}"
                    .getBytes(StandardCharsets.UTF_8);

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
    void repeatedTokenRequestsMustNotAccumulateAcceptHeaders() throws TokenException {
        GitLabTokenService service = new GitLabTokenService();
        ReflectionTestUtils.setField(service, "hostname", "localhost");
        ReflectionTestUtils.setField(service, "webClientBuilder", WebClient.builder());

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();

        for (int i = 0; i < 5; i++) {
            GitLabToken token = service.getAccessToken(
                    "vcs-1", "client-id", "client-secret",
                    "temp-code", null, endpoint);

            assertNotNull(token);
            assertEquals("test-token", token.getAccess_token());
        }

        assertEquals(5, receivedAcceptHeaders.size(),
                "expected 5 requests to mock server");

        for (int i = 0; i < receivedAcceptHeaders.size(); i++) {
            List<String> accepts = receivedAcceptHeaders.get(i);
            assertEquals(1, accepts.size(),
                    "request " + (i + 1) + " sent " + accepts.size()
                            + " Accept header values: " + accepts);
            assertEquals("application/json", accepts.get(0));
        }
    }

    @Test
    void shouldUseVcsHostnameForOAuthRedirectUri() throws TokenException {
        GitLabTokenService service = new GitLabTokenService();
        ReflectionTestUtils.setField(service, "hostname", "app.example.com");
        ReflectionTestUtils.setField(service, "vcsHostname", "vcs.example.com");
        ReflectionTestUtils.setField(service, "webClientBuilder", WebClient.builder());

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();

        service.getAccessToken(
                "vcs-1", "client-id", "client-secret",
                "temp-code", null, endpoint);

        assertEquals(
                "https://vcs.example.com/callback/v1/vcs/vcs-1",
                receivedRedirectUris.get(0));
    }

    @Test
    void shouldFallBackToGeneralHostnameWhenVcsHostnameIsEmpty()
            throws TokenException {
        GitLabTokenService service = new GitLabTokenService();
        ReflectionTestUtils.setField(service, "hostname", "app.example.com");
        ReflectionTestUtils.setField(service, "vcsHostname", "");
        ReflectionTestUtils.setField(service, "webClientBuilder", WebClient.builder());

        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();

        service.getAccessToken(
                "vcs-1", "client-id", "client-secret",
                "temp-code", null, endpoint);

        assertEquals(
                "https://app.example.com/callback/v1/vcs/vcs-1",
                receivedRedirectUris.get(0));
    }
}