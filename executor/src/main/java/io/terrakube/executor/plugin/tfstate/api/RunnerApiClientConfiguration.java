package io.terrakube.executor.plugin.tfstate.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "io.terrakube.executor.flags", name = "hardened", havingValue = "true")
public class RunnerApiClientConfiguration {

    @Bean
    public RunnerApiClient runnerApiClient(@Value("${io.terrakube.api.url}") String apiUrl) {
        return new RunnerApiClient(apiUrl);
    }
}
