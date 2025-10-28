package io.terrakube.api.plugin.json;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.io.File;
import java.util.List;

@AllArgsConstructor
@Slf4j
@Service
public class DownloadReleasesService {

    private WebClient.Builder webClientBuilder;

    public void downloadReleasesToFile(String releasesUrl, File releasesFile) {
        WebClient webClient = webClientBuilder
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create()
                                .followRedirect(true)
                                .proxyWithSystemProperties()
                ))
                .defaultHeaders(h -> {
                    h.add("User-Agent", "releases-downloader");
                    h.setAccept(List.of(MediaType.APPLICATION_JSON));
                })
                .build();

        webClient.get()
                .uri(releasesUrl)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        clientResponse -> clientResponse.createException().flatMap(Mono::error)
                )
                .bodyToFlux(DataBuffer.class)
                .as(dataBufferFlux -> DataBufferUtils.write(dataBufferFlux, releasesFile.toPath() ))
                .then()
                .block();
    }
}
