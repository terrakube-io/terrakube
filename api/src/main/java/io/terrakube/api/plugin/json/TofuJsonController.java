package io.terrakube.api.plugin.json;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.io.IOUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("/tofu")
public class TofuJsonController {

    private static final String TOFU_REDIS_KEY = "tofuReleasesResponse";
    TofuJsonProperties tofuJsonProperties;
    RedisTemplate redisTemplate;
    private WebClient.Builder webClientBuilder;

    @GetMapping(value= "/index.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getTofuReleases() throws IOException {
        String tofuIndex = "";
        if(redisTemplate.hasKey(TOFU_REDIS_KEY)) {
            log.info("Getting tofu releases from redis....");
            String tofuRedis = (String) redisTemplate.opsForValue().get(TOFU_REDIS_KEY);
            return new ResponseEntity<>(tofuRedis, HttpStatus.OK);
        } else {
            log.info("Getting tofu releases from default endpoint....");
            if(tofuJsonProperties.getReleasesUrl() != null && !tofuJsonProperties.getReleasesUrl().isEmpty()) {
                log.info("Using tofu releases URL {}", tofuJsonProperties.getReleasesUrl());
                tofuIndex = tofuJsonProperties.getReleasesUrl();
            } else {
                tofuIndex = "https://api.github.com/repos/opentofu/opentofu/releases";
                log.warn("Using tofu releases URL {}", tofuIndex);
            }

            WebClient webClient = webClientBuilder
                    .exchangeStrategies(ExchangeStrategies.builder()
                            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                            .build())
                    .baseUrl(tofuIndex)
                    .clientConnector(
                            new ReactorClientHttpConnector(
                                    HttpClient.create().proxyWithSystemProperties())
                    ).build();

            try {
                String tofuIndexResponse = webClient.get()
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();

                log.warn("Saving tofu releases to redis...");
                redisTemplate.opsForValue().set(TOFU_REDIS_KEY, tofuIndexResponse);
                redisTemplate.expire(TOFU_REDIS_KEY, 30, TimeUnit.MINUTES);
                return new ResponseEntity<>(tofuIndexResponse, HttpStatus.OK);
            } catch (Exception e) {
                log.error(e.getMessage());
                return new ResponseEntity<>("", HttpStatus.INTERNAL_SERVER_ERROR);
            }

        }

    }
}


