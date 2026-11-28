package io.terrakube.registry.configuration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Feign;
import feign.Request;
import feign.http2client.Http2Client;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.spring.autoconfigure.RestClientProperties;
import io.terrakube.registry.service.token.RegistryTokenService;
import io.terrakube.registry.service.token.TokenStatusClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
@EnableConfigurationProperties(RestClientProperties.class)
public class TerrakubeClientConfiguration {

    @Bean
    public TerrakubeClient terrakubeClient(
            RestClientProperties restClientProperties,
            RegistryTokenService registryTokenService) {
        return buildFeignBuilder(restClientProperties, registryTokenService)
                .target(TerrakubeClient.class, restClientProperties.getUrl());
    }

    @Bean
    public TokenStatusClient tokenStatusClient(
            RestClientProperties restClientProperties,
            RegistryTokenService registryTokenService) {
        return buildFeignBuilder(restClientProperties, registryTokenService)
                .options(new Request.Options(5, TimeUnit.SECONDS, 5, TimeUnit.SECONDS, true))
                .target(TokenStatusClient.class, restClientProperties.getUrl());
    }

    private Feign.Builder buildFeignBuilder(
            RestClientProperties restClientProperties,
            RegistryTokenService registryTokenService) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        Feign.Builder builder = Feign.builder()
                .encoder(new JacksonEncoder(objectMapper))
                .decoder(new JacksonDecoder(objectMapper))
                .client(new Http2Client());

        if (restClientProperties.isEnableSecurity()) {
            builder.requestInterceptor(template ->
                    template.header("Authorization", "Bearer " + registryTokenService.generateInternalToken()));
        }
        return builder;
    }
}
