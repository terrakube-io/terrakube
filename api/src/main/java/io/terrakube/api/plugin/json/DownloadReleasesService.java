package io.terrakube.api.plugin.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.api.plugin.http.ReactorNettyWebClientFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class DownloadReleasesService {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration OVERALL_TIMEOUT = Duration.ofSeconds(60);

    // GitHub returns 30 releases per page unless asked for more (100 at most), so without paging only the
    // newest 30 releases are listed. The page cap is a safety net against a next link that never ends.
    static final int GITHUB_PER_PAGE = 100;
    static final int MAX_PAGES = 20;
    // A whole page is held in memory to read its JSON; the OpenTofu page of 100 releases is ~7 MB.
    private static final int MAX_PAGE_BYTES = 64 * 1024 * 1024;
    // One link-value of a Link header: the URL, then its parameters up to the next link.
    private static final Pattern LINK_VALUE = Pattern.compile("<([^>]*)>([^<]*)");
    private static final Pattern LINK_REL = Pattern.compile("(?:^|;)\\s*rel\\s*=\\s*(?:\"([^\"]*)\"|([^\\s;,]+))",
            Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WebClient.Builder webClientBuilder;
    private final Duration responseTimeout;
    private final Duration overallTimeout;

    /**
     * Releases read from a paginated list. {@code complete} is false when a page after the first one could
     * not be read (rate limit, timeout, bad JSON, page cap, a link to another origin): the releases then hold
     * only the pages read before it.
     */
    public record ReleasesPages(ArrayNode releases, boolean complete) {
    }

    @Autowired
    public DownloadReleasesService(WebClient.Builder webClientBuilder) {
        this(webClientBuilder, RESPONSE_TIMEOUT, OVERALL_TIMEOUT);
    }

    DownloadReleasesService(WebClient.Builder webClientBuilder, Duration responseTimeout, Duration overallTimeout) {
        this.webClientBuilder = webClientBuilder;
        this.responseTimeout = responseTimeout;
        this.overallTimeout = overallTimeout;
    }

    public void downloadReleasesToFile(String releasesUrl, File releasesFile) {
        downloadReleasesToFile(releasesUrl, releasesFile, null);
    }

    public void downloadReleasesToFile(String releasesUrl, File releasesFile, String githubToken) {
        webClient(githubToken).get()
                .uri(releasesUrl)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        clientResponse -> {
                            log.error("Failed to download releases from {}: HTTP {}",
                                releasesUrl, clientResponse.statusCode());
                            return clientResponse.createException().flatMap(Mono::error);
                        }
                )
                .bodyToFlux(DataBuffer.class)
                .as(dataBufferFlux -> DataBufferUtils.write(dataBufferFlux, releasesFile.toPath()))
                .then()
                // responseTimeout protects the wait for an HTTP response, while this also
                // bounds redirects and a response body which stops making progress.
                .timeout(overallTimeout)
                .block();
    }

    /**
     * Reads a JSON array of releases page by page, following the {@code Link: <...>; rel="next"} header, and
     * returns all pages as one array. A response without a next link, such as a mirror serving a static file,
     * is the whole list. GitHub URLs are asked for {@value #GITHUB_PER_PAGE} releases per page; any other URL
     * is requested exactly as configured, since a pre-signed S3/GCS URL signs its query string.
     * <p>
     * The overall timeout covers all pages together. Only a failure of the first page throws; a later failure
     * returns the pages read so far with {@code complete = false}. Next links are only followed on the origin
     * of the configured URL, so the GitHub token is never sent to another host.
     */
    public ReleasesPages downloadAllReleases(String releasesUrl, String githubToken) {
        WebClient webClient = webClient(githubToken);
        long deadline = System.nanoTime() + overallTimeout.toNanos();
        URI firstPage = URI.create(withGithubPerPage(releasesUrl));
        URI pageUrl = firstPage;
        Set<URI> visitedPages = new HashSet<>();
        visitedPages.add(pageUrl);
        ArrayNode releases = OBJECT_MAPPER.createArrayNode();
        for (int page = 1; ; page++) {
            URI nextPageUrl;
            try {
                Page response = downloadPage(webClient, pageUrl, Duration.ofNanos(deadline - System.nanoTime()));
                releases.addAll(response.releases());
                nextPageUrl = nextPageUrl(pageUrl, response.linkHeaders());
            } catch (RuntimeException | IOException | TimeoutException e) {
                if (page == 1) {
                    throw e instanceof RuntimeException runtimeException
                            ? runtimeException
                            : new IllegalStateException("Failed to download releases from " + releasesUrl, e);
                }
                log.warn("Unable to read page {} of the releases from {}, using the {} releases read so far: {}",
                        page, pageUrl, releases.size(), e.getMessage());
                return new ReleasesPages(releases, false);
            }
            if (nextPageUrl == null) {
                return new ReleasesPages(releases, true);
            }
            if (!visitedPages.add(nextPageUrl)) {
                log.warn("Stopped reading releases from {}: page {} links back to {}", releasesUrl, page, nextPageUrl);
                return new ReleasesPages(releases, false);
            }
            if (!sameOrigin(firstPage, nextPageUrl)) {
                log.warn("Stopped reading releases from {}: page {} links to another origin {}", releasesUrl, page,
                        nextPageUrl);
                return new ReleasesPages(releases, false);
            }
            if (page == MAX_PAGES) {
                log.warn("Stopped reading releases from {} after {} pages, older releases are not listed",
                        releasesUrl, MAX_PAGES);
                return new ReleasesPages(releases, false);
            }
            pageUrl = nextPageUrl;
        }
    }

    private record Page(ArrayNode releases, List<String> linkHeaders) {
    }

    private Page downloadPage(WebClient webClient, URI pageUrl, Duration remaining)
            throws IOException, TimeoutException {
        if (remaining.isNegative() || remaining.isZero()) {
            throw new TimeoutException("Download of the releases did not complete within " + overallTimeout);
        }
        record RawPage(byte[] body, List<String> linkHeaders) {
        }
        RawPage rawPage = webClient.get()
                .uri(pageUrl)
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        log.error("Failed to download releases from {}: HTTP {}", pageUrl, response.statusCode());
                        return response.createException().flatMap(Mono::error);
                    }
                    List<String> linkHeaders = response.headers().header("Link");
                    return DataBufferUtils.join(response.bodyToFlux(DataBuffer.class), MAX_PAGE_BYTES)
                            .map(buffer -> {
                                try (InputStream body = buffer.asInputStream(true)) {
                                    return new RawPage(body.readAllBytes(), linkHeaders);
                                } catch (IOException e) {
                                    throw new IllegalStateException(e);
                                }
                            })
                            .defaultIfEmpty(new RawPage(new byte[0], linkHeaders));
                })
                .timeout(remaining)
                .block();
        JsonNode body = OBJECT_MAPPER.readTree(Objects.requireNonNull(rawPage).body());
        if (body == null || !body.isArray()) {
            throw new IOException("Releases page " + pageUrl + " is not a JSON array");
        }
        return new Page((ArrayNode) body, rawPage.linkHeaders());
    }

    private WebClient webClient(String githubToken) {
        // clone() is essential: webClientBuilder is a field on this singleton bean, and
        // DefaultWebClientBuilder.defaultHeaders() applies the consumer immediately to the
        // builder's own HttpHeaders. Mutating the shared builder would make h.add("User-Agent", ..)
        // below append one more value on every call, growing the header until the remote
        // rejects the request (api.github.com starts returning 400/500 past ~7KB of headers).
        return webClientBuilder
                .clone()
                .clientConnector(ReactorNettyWebClientFactory.redirectingConnector(responseTimeout))
                .defaultHeaders(h -> {
                    h.add("User-Agent", "releases-downloader");
                    h.setAccept(List.of(MediaType.APPLICATION_JSON));

                    if (githubToken != null && !githubToken.isEmpty()) {
                        h.setBearerAuth(githubToken);
                        log.debug("Using authenticated GitHub API request");
                    } else {
                        log.warn("No GitHub token provided - using unauthenticated request (subject to rate limits)");
                    }
                })
                .build();
    }

    /**
     * Asks GitHub (api.github.com or GitHub Enterprise {@code /api/v3/}) for its largest page, unless the URL
     * already chose a page size. Any other URL is returned unchanged.
     */
    static String withGithubPerPage(String releasesUrl) {
        URI uri;
        try {
            uri = new URI(releasesUrl);
        } catch (URISyntaxException e) {
            return releasesUrl;
        }
        String path = uri.getRawPath();
        boolean gitHub = "api.github.com".equalsIgnoreCase(uri.getHost()) || (path != null && path.startsWith("/api/v3/"));
        String query = uri.getRawQuery();
        if (!gitHub || (query != null && Arrays.stream(query.split("&"))
                .anyMatch(p -> p.equals("per_page") || p.startsWith("per_page=")))) {
            return releasesUrl;
        }
        int fragmentStart = releasesUrl.indexOf('#');
        String withoutFragment = fragmentStart < 0 ? releasesUrl : releasesUrl.substring(0, fragmentStart);
        String fragment = fragmentStart < 0 ? "" : releasesUrl.substring(fragmentStart);
        String separator = query == null ? "?" : query.isEmpty() ? "" : "&";
        return withoutFragment + separator + "per_page=" + GITHUB_PER_PAGE + fragment;
    }

    /**
     * Finds the link whose rel has the "next" token across all Link headers. A relative link is resolved
     * against the page it came from.
     */
    static URI nextPageUrl(URI pageUrl, List<String> linkHeaders) {
        if (linkHeaders == null) {
            return null;
        }
        Matcher link = LINK_VALUE.matcher(String.join(",", linkHeaders));
        while (link.find()) {
            Matcher rel = LINK_REL.matcher(link.group(2));
            if (rel.find()) {
                String relValue = rel.group(1) != null ? rel.group(1) : rel.group(2);
                if (Arrays.stream(relValue.trim().split("\\s+")).anyMatch("next"::equalsIgnoreCase)) {
                    try {
                        return pageUrl.resolve(link.group(1).trim());
                    } catch (IllegalArgumentException e) {
                        log.warn("Ignoring next page link {} from {}: {}", link.group(1), pageUrl, e.getMessage());
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static boolean sameOrigin(URI first, URI other) {
        return first.getScheme() != null && first.getScheme().equalsIgnoreCase(other.getScheme())
                && first.getHost() != null && first.getHost().equalsIgnoreCase(other.getHost())
                && port(first) == port(other);
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : -1;
    }
}
