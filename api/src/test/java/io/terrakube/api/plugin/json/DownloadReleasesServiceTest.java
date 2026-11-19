package io.terrakube.api.plugin.json;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadReleasesServiceTest {

    private HttpServer server;
    private final List<List<String>> receivedUserAgents = new ArrayList<>();
    private final CountDownLatch stalledResponseLatch = new CountDownLatch(1);
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
        server.createContext("/stalled", exchange -> {
            try {
                stalledResponseLatch.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        stalledResponseLatch.countDown();
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

    @Test
    void stalledResponseMustTimeOut() {
        DownloadReleasesService service = new DownloadReleasesService(
                WebClient.builder(), Duration.ofMillis(100), Duration.ofMillis(200));
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/stalled";

        long startedAt = System.nanoTime();
        assertThrows(RuntimeException.class,
                () -> service.downloadReleasesToFile(url, new File(tempDir.toFile(), "stalled.json")));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertTrue(elapsedMillis < Duration.ofSeconds(2).toMillis(),
                "stalled release request was not bounded: " + elapsedMillis + "ms");
    }

    @Test
    void redirectWithLargeCspHeaderSucceeds() throws IOException {
        server.createContext("/redirect-large-csp", exchange -> {
            exchange.getResponseHeaders().add("Location", "/releases");
            exchange.getResponseHeaders().add("Content-Security-Policy", "connect-src " + "x".repeat(10_000));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        DownloadReleasesService service = new DownloadReleasesService(WebClient.builder());
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect-large-csp";
        File targetFile = new File(tempDir.toFile(), "large-csp-releases.json");

        service.downloadReleasesToFile(url, targetFile);

        assertTrue(targetFile.exists(), "Target file should exist after following redirect with large CSP header");
        assertEquals("[]", Files.readString(targetFile.toPath(), StandardCharsets.UTF_8));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void page(String path, String body, String... linkHeaders) {
        page(path, 200, body, linkHeaders);
    }

    private void page(String path, int status, String body, String... linkHeaders) {
        server.createContext(path, exchange -> {
            for (String link : linkHeaders) {
                exchange.getResponseHeaders().add("Link", link.replace("BASE", baseUrl()));
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private static List<String> tags(JsonNode releases) {
        List<String> tags = new ArrayList<>();
        releases.forEach(release -> tags.add(release.path("tag_name").asText()));
        return tags;
    }

    @Test
    void followsNextLinksAndReturnsAllPages() {
        page("/paged/1", "[{\"tag_name\":\"v1.10.0\"},{\"tag_name\":\"v1.9.0\"}]",
                "<BASE/paged/2>; rel=\"next\", <BASE/paged/3>; rel=\"last\"");
        page("/paged/2", "[{\"tag_name\":\"v1.8.0\"}]", "</paged/3>; rel=\"next\"");
        page("/paged/3", "[{\"tag_name\":\"v1.6.0\"}]", "<BASE/paged/1>; rel=\"first\"");

        DownloadReleasesService.ReleasesPages pages = new DownloadReleasesService(WebClient.builder())
                .downloadAllReleases(baseUrl() + "/paged/1", null);

        assertTrue(pages.complete());
        assertEquals(List.of("v1.10.0", "v1.9.0", "v1.8.0", "v1.6.0"), tags(pages.releases()));
    }

    @Test
    void singlePageWithoutLinkIsTheWholeList() {
        page("/mirror.json", "[{\"tag_name\":\"v1.6.0\"}]");

        DownloadReleasesService.ReleasesPages pages = new DownloadReleasesService(WebClient.builder())
                .downloadAllReleases(baseUrl() + "/mirror.json?X-Amz-Signature=abc", null);

        assertTrue(pages.complete());
        assertEquals(List.of("v1.6.0"), tags(pages.releases()));
    }

    @Test
    void laterPageFailureKeepsThePagesReadSoFar() {
        page("/limited/1", "[{\"tag_name\":\"v1.10.0\"}]", "<BASE/limited/2>; rel=\"next\"");
        page("/limited/2", 403, "{\"message\":\"API rate limit exceeded\"}");

        DownloadReleasesService.ReleasesPages pages = new DownloadReleasesService(WebClient.builder())
                .downloadAllReleases(baseUrl() + "/limited/1", null);

        assertFalse(pages.complete());
        assertEquals(List.of("v1.10.0"), tags(pages.releases()));
    }

    @Test
    void firstPageFailureThrows() {
        page("/down", 500, "oops");

        DownloadReleasesService service = new DownloadReleasesService(WebClient.builder());
        assertThrows(RuntimeException.class, () -> service.downloadAllReleases(baseUrl() + "/down", null));
    }

    @Test
    void firstPageThatIsNotAnArrayThrows() {
        page("/object", "{\"message\":\"Not Found\"}");

        DownloadReleasesService service = new DownloadReleasesService(WebClient.builder());
        assertThrows(RuntimeException.class, () -> service.downloadAllReleases(baseUrl() + "/object", null));
    }

    @Test
    void doesNotFollowNextLinkToAnotherOrigin() {
        List<String> authorizations = new ArrayList<>();
        server.createContext("/other-origin", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("[]".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        // localhost and 127.0.0.1 reach the same server but are different origins.
        page("/origin/1", "[{\"tag_name\":\"v1.10.0\"}]",
                "<http://localhost:" + server.getAddress().getPort() + "/other-origin>; rel=\"next\"");

        DownloadReleasesService.ReleasesPages pages = new DownloadReleasesService(WebClient.builder())
                .downloadAllReleases(baseUrl() + "/origin/1", "secret-token");

        assertFalse(pages.complete());
        assertEquals(List.of("v1.10.0"), tags(pages.releases()));
        assertTrue(authorizations.isEmpty(), "the token was sent to another origin");
    }

    @Test
    void stopsOnALinkLoop() {
        page("/loop/1", "[{\"tag_name\":\"v1.10.0\"}]", "<BASE/loop/2>; rel=\"next\"");
        page("/loop/2", "[{\"tag_name\":\"v1.9.0\"}]", "<BASE/loop/1>; rel=\"next\"");

        DownloadReleasesService.ReleasesPages pages = new DownloadReleasesService(WebClient.builder())
                .downloadAllReleases(baseUrl() + "/loop/1", null);

        assertFalse(pages.complete());
        assertEquals(List.of("v1.10.0", "v1.9.0"), tags(pages.releases()));
    }

    @Test
    void stopsAfterThePageCap() {
        server.createContext("/endless", exchange -> {
            int page = Integer.parseInt(exchange.getRequestURI().getQuery().replace("page=", ""));
            exchange.getResponseHeaders().add("Link", "</endless?page=" + (page + 1) + ">; rel=\"next\"");
            byte[] bytes = ("[{\"tag_name\":\"v0." + page + ".0\"}]").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        DownloadReleasesService.ReleasesPages pages = new DownloadReleasesService(WebClient.builder())
                .downloadAllReleases(baseUrl() + "/endless?page=1", null);

        assertFalse(pages.complete());
        assertEquals(DownloadReleasesService.MAX_PAGES, pages.releases().size());
    }

    @Test
    void deadlineCoversAllPages() {
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int page = Integer.parseInt(exchange.getRequestURI().getQuery().replace("page=", ""));
            exchange.getResponseHeaders().add("Link", "</slow?page=" + (page + 1) + ">; rel=\"next\"");
            byte[] bytes = ("[{\"tag_name\":\"v0." + page + ".0\"}]").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        long startedAt = System.nanoTime();
        DownloadReleasesService.ReleasesPages pages = new DownloadReleasesService(
                WebClient.builder(), Duration.ofSeconds(5), Duration.ofMillis(500))
                .downloadAllReleases(baseUrl() + "/slow?page=1", null);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertFalse(pages.complete());
        assertTrue(pages.releases().size() < DownloadReleasesService.MAX_PAGES);
        assertTrue(elapsedMillis < Duration.ofSeconds(2).toMillis(), "paging was not bounded: " + elapsedMillis + "ms");
    }

    @Test
    void perPageIsOnlyAddedForGithub() {
        assertEquals("https://api.github.com/repos/opentofu/opentofu/releases?per_page=100",
                DownloadReleasesService.withGithubPerPage("https://api.github.com/repos/opentofu/opentofu/releases"));
        assertEquals("https://ghe.example.com/api/v3/repos/o/r/releases?a=b&per_page=100#x",
                DownloadReleasesService.withGithubPerPage("https://ghe.example.com/api/v3/repos/o/r/releases?a=b#x"));
        assertEquals("https://api.github.com/repos/o/r/releases?per_page=10",
                DownloadReleasesService.withGithubPerPage("https://api.github.com/repos/o/r/releases?per_page=10"));
        assertEquals("https://bucket.s3.amazonaws.com/tofu.json?X-Amz-Signature=abc",
                DownloadReleasesService.withGithubPerPage("https://bucket.s3.amazonaws.com/tofu.json?X-Amz-Signature=abc"));
    }

    @Test
    void nextLinkIsFoundAcrossLinkHeadersByRelToken() {
        URI page = URI.create("https://api.github.com/repositories/1/releases?page=1");

        assertEquals(URI.create("https://api.github.com/repositories/1/releases?page=2"),
                DownloadReleasesService.nextPageUrl(page, List.of(
                        "<https://api.github.com/x?page=9>; rel=\"next-page\"",
                        "<https://api.github.com/repositories/1/releases?page=2>; rel=\"prefetch next\"")));
        assertNull(DownloadReleasesService.nextPageUrl(page, List.of("<https://api.github.com/x>; rel=\"last\"")));
        assertNull(DownloadReleasesService.nextPageUrl(page, List.of()));
    }
}
