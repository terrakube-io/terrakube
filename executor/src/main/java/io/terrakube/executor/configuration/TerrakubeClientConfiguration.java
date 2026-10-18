package io.terrakube.executor.configuration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Feign;
import feign.http2client.Http2Client;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.spring.autoconfigure.RestClientProperties;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
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
            WorkspaceSecurity workspaceSecurity) {
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
                    String token = workspaceSecurity.generateAccessToken(1);
                    template.header("Authorization", "Bearer " + token);
                })
                .target(TerrakubeClient.class, restClientProperties.getUrl());
    }
}
