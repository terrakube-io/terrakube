package io.terrakube.api.plugin.json;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadReleasesServiceTest {

    private HttpServer server;
    private final List<List<String>> receivedUserAgents = new ArrayList<>();
    private Path tempDir;

    @BeforeEach
    void startServer() throws IOException {
        tempDir = Files.createTempDirectory("releases-test");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/releases", exchange -> {
            List<String> userAgents = exchange.getRequestHeaders().get("User-Agent");
            receivedUserAgents.add(userAgents == null ? List.of() : List.copyOf(userAgents));
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
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

    /**
     * The builder is a field on this singleton bean and
     * DefaultWebClientBuilder.defaultHeaders() mutates it in place, so without clone()
     * every call appends another User-Agent value. Left unchecked the header grows past
     * what remotes accept - api.github.com answers 400/500 beyond roughly 7KB of headers,
     * which breaks /tofu/index.json and leaves executor jobs unable to resolve releases.
     */
    @Test
    void repeatedDownloadsMustNotAccumulateHeaders() {
        DownloadReleasesService service = new DownloadReleasesService(WebClient.builder());
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/releases";

        for (int i = 0; i < 5; i++) {
            service.downloadReleasesToFile(url, new File(tempDir.toFile(), "releases-" + i + ".json"));
        }

        assertEquals(5, receivedUserAgents.size(), "expected one request per download");
        for (int i = 0; i < receivedUserAgents.size(); i++) {
            List<String> userAgents = receivedUserAgents.get(i);
            assertEquals(1, userAgents.size(),
                    "request " + (i + 1) + " sent " + userAgents.size() + " User-Agent values: " + userAgents);
            assertTrue(userAgents.get(0).equals("releases-downloader"),
                    "unexpected User-Agent: " + userAgents.get(0));
        }
    }
}
