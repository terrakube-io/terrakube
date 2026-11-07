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
    void everyCacheTheServiceAnnotationsAskForExists() {
        CacheManager cacheManager = new CacheConfig().cacheManager(properties(600));

        assertThat(cacheManager.getCacheNames()).containsExactlyInAnyOrder(
                CacheConfig.MODULE_VERSIONS_CACHE,
                CacheConfig.MODULE_VERSION_PATH_CACHE,
                CacheConfig.MODULE_VERSION_REMOVED_CACHE,
                CacheConfig.PROVIDER_WARNINGS_CACHE,
                CacheConfig.MODULE_DETAILS_CACHE,
                CacheConfig.PROVIDER_VERSIONS_CACHE,
                CacheConfig.PROVIDER_FILE_CACHE,
                CacheConfig.ORGANIZATION_ID_CACHE);
        cacheManager.getCacheNames().forEach(name -> assertThat(cacheManager.getCache(name)).isNotNull());
    }

    @Test
    void aNonPositiveProviderTtlIsRejected() {
        OpenRegistryProperties properties = properties(600);
        properties.setProviderCacheTtlSeconds(0);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("providerCacheTtlSeconds");
    }

    @Test
    void providerCachesExpireAfterTheConfiguredTtl() {
        OpenRegistryProperties properties = properties(600);
        properties.setProviderCacheTtlSeconds(7);
        CacheManager cacheManager = new CacheConfig().cacheManager(properties);

        for (String name : new String[]{CacheConfig.PROVIDER_VERSIONS_CACHE, CacheConfig.PROVIDER_FILE_CACHE}) {
            com.github.benmanes.caffeine.cache.Cache<?, ?> nativeCache =
                    (com.github.benmanes.caffeine.cache.Cache<?, ?>) cacheManager.getCache(name).getNativeCache();
            assertThat(nativeCache.policy().expireAfterWrite().orElseThrow().getExpiresAfter(java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(7);
        }
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
