package io.terrakube.registry.configuration;

import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class CacheConfig {

    /** Version list of one module. Goes stale as soon as a new module version is released. */
    public static final String MODULE_VERSIONS_CACHE = "getAvailableVersions";

    /** Resolved download path of one module version. The version is part of the key. */
    public static final String MODULE_VERSION_PATH_CACHE = "getModuleVersionPath";

    @Bean
    public CacheManager cacheManager(OpenRegistryProperties openRegistryProperties) {
        Duration versionsTtl = Duration.ofSeconds(openRegistryProperties.getModuleVersionsCacheTtlSeconds());
        log.info("Module registry cache: versions ttl {}", versionsTtl);

        // Naming the caches keeps the manager static: an unknown @Cacheable name then fails loudly.
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(MODULE_VERSIONS_CACHE,
                MODULE_VERSION_PATH_CACHE);
        cacheManager.setCaffeine(Caffeine.newBuilder().recordStats()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(1000));

        // Only the version list needs a knob. The path cache has the version in its key.
        cacheManager.registerCustomCache(MODULE_VERSIONS_CACHE, Caffeine.newBuilder().recordStats()
                .expireAfterWrite(versionsTtl)
                .maximumSize(1000)
                .build());
        return cacheManager;
    }
}
