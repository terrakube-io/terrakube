package io.terrakube.registry.configuration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Feign;
import feign.http2client.Http2Client;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.spring.autoconfigure.RestClientProperties;
import io.terrakube.registry.service.token.RegistryTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@EnableConfigurationProperties(RestClientProperties.class)
public class TerrakubeClientConfiguration {

    @Bean
    public TerrakubeClient terrakubeClient(
            RestClientProperties restClientProperties,
            RegistryTokenService registryTokenService) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        if (!restClientProperties.isEnableSecurity()) {
            return Feign.builder()
                    .encoder(new JacksonEncoder(objectMapper))
                    .decoder(new JacksonDecoder(objectMapper))
                    .client(new Http2Client())
                    .target(TerrakubeClient.class, restClientProperties.getUrl());
        }

        return Feign.builder()
                .encoder(new JacksonEncoder(objectMapper))
                .decoder(new JacksonDecoder(objectMapper))
                .client(new Http2Client())
                .requestInterceptor(template -> {
                    template.header("Authorization", "Bearer " + registryTokenService.generateInternalToken());
                })
                .target(TerrakubeClient.class, restClientProperties.getUrl());
    }
}
