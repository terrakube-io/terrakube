package io.terrakube.executor.configuration.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

@Slf4j
public class ExecutorSecretEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String PROPERTY_SOURCE_NAME = "terrakubeEphemeralSecrets";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        try {
            Map<String, Object> ephemeralSecrets = InternalSecretLoader.loadSecrets(environment);
            if (!ephemeralSecrets.isEmpty()) {
                log.info("Loaded and shredded {} ephemeral secret properties into Spring Environment", ephemeralSecrets.size());
                environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, ephemeralSecrets));
            }
        } catch (IllegalStateException e) {
            log.error("Fatal error loading ephemeral secrets: {}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("Failed to post-process ephemeral secrets into environment: {}", e.getMessage(), e);
        }
    }

    @Override
    public int getOrder() {
        // Runs at lowest precedence among environment post-processors so that
        // application.properties / application.yml are already loaded and their default
        // file paths or directory configurations are accessible, while executing well before
        // beans or @ConfigurationProperties are bound.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
