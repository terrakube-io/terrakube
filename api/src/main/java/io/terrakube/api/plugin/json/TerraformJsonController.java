package io.terrakube.api.plugin.json;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.io.IOUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;

@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("/terraform")
public class TerraformJsonController {

    private TerraformJsonProperties terraformJsonProperties;
    private WebClient.Builder webClientBuilder;

    @GetMapping(value = "/index.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> createToken() throws IOException {

        String terraformIndex = "";
        if (terraformJsonProperties.getReleasesUrl() != null && !terraformJsonProperties.getReleasesUrl().isEmpty()) {
            log.info("Using terraform releases URL {}", terraformJsonProperties.getReleasesUrl());
            terraformIndex = terraformJsonProperties.getReleasesUrl();
        } else {
            log.warn("Using terraform releases URL https://releases.hashicorp.com/terraform/index.json");
            terraformIndex = "https://releases.hashicorp.com/terraform/index.json";
        }

        WebClient webClient = webClientBuilder
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                        .build())
                .baseUrl(terraformIndex)
                .clientConnector(
                        new ReactorClientHttpConnector(
                                HttpClient.create().proxyWithSystemProperties())
                )
                .build();

        try {
            terraformIndex = webClient.get()
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return new ResponseEntity<>(terraformIndex, HttpStatus.OK);
        } catch (Exception e) {
            log.error(e.getMessage());
            return new ResponseEntity<>(terraformIndex, HttpStatus.INTERNAL_SERVER_ERROR);
        }


    }
}


