package io.terrakube.api.plugin.json;

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
import java.time.Duration;
import java.util.List;

@Slf4j
@Service
public class DownloadReleasesService {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration OVERALL_TIMEOUT = Duration.ofSeconds(60);

    private final WebClient.Builder webClientBuilder;
    private final Duration responseTimeout;
    private final Duration overallTimeout;

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
        // clone() is essential: webClientBuilder is a field on this singleton bean, and
        // DefaultWebClientBuilder.defaultHeaders() applies the consumer immediately to the
        // builder's own HttpHeaders. Mutating the shared builder would make h.add("User-Agent", ..)
        // below append one more value on every call, growing the header until the remote
        // rejects the request (api.github.com starts returning 400/500 past ~7KB of headers).
        WebClient webClient = webClientBuilder
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

        webClient.get()
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
}
