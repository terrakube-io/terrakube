package io.terrakube.registry.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheConfigTest {

    private static OpenRegistryProperties properties(long versionsTtlSeconds) {
        OpenRegistryProperties properties = new OpenRegistryProperties();
        properties.setModuleVersionsCacheTtlSeconds(versionsTtlSeconds);
        return properties;
    }

    /** A cache the @Cacheable annotations ask for but the configuration misses fails at first call. */
    @Test
    void bothCachesTheServiceAnnotationsAskForExist() {
        CacheManager cacheManager = new CacheConfig().cacheManager(properties(600));

        assertThat(cacheManager.getCache(CacheConfig.MODULE_VERSIONS_CACHE)).isNotNull();
        assertThat(cacheManager.getCache(CacheConfig.MODULE_VERSION_PATH_CACHE)).isNotNull();
        assertThat(cacheManager.getCacheNames())
                .containsExactlyInAnyOrder(CacheConfig.MODULE_VERSIONS_CACHE, CacheConfig.MODULE_VERSION_PATH_CACHE);
    }

    @Test
    void anUnknownCacheNameIsRefusedRatherThanCreated() {
        CacheManager cacheManager = new CacheConfig().cacheManager(properties(600));

        assertThat(cacheManager.getCache("typoInACacheableAnnotation")).isNull();
    }

    /** Caffeine reads a zero expiry as "expire immediately", which disables the cache. */
    @Test
    void aNonPositiveTtlIsRejected() {
        assertThatThrownBy(() -> properties(0).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("moduleVersionsCacheTtlSeconds");
        assertThatThrownBy(() -> properties(-1).validate())
                .isInstanceOf(IllegalStateException.class);
    }
}
