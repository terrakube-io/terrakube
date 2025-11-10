package io.terrakube.registry.configuration;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("getAvailableVersions", "getModuleVersionPath");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(10    , TimeUnit.MINUTES)
                .maximumSize(1000));
        return cacheManager;
    }
}
