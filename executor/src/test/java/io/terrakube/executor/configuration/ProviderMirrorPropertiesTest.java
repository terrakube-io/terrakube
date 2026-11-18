package io.terrakube.executor.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderMirrorPropertiesTest {

    @EnableConfigurationProperties(ProviderMirrorProperties.class)
    static class TestConfig {}

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

    @Test
    void isOffByDefault() {
        contextRunner.run(context -> {
            ProviderMirrorProperties props = context.getBean(ProviderMirrorProperties.class);
            assertThat(props.isEnabled()).isFalse();
            assertThat(props.isOnly()).isFalse();
            assertThat(props.getRegistryUrl()).isEmpty();
        });
    }

    @Test
    void bindsThePropertyNames() {
        contextRunner
                .withPropertyValues(
                        "io.terrakube.provider-mirror.enabled=true",
                        "io.terrakube.provider-mirror.only=true",
                        "io.terrakube.provider-mirror.registry-url=https://registry.example.com")
                .run(context -> {
                    ProviderMirrorProperties props = context.getBean(ProviderMirrorProperties.class);
                    assertThat(props.isEnabled()).isTrue();
                    assertThat(props.isOnly()).isTrue();
                    assertThat(props.getRegistryUrl()).isEqualTo("https://registry.example.com");
                });
    }

    @Test
    void theEnvironmentVariableNamesInApplicationPropertiesReachTheProperties() throws Exception {
        java.util.Properties file = org.springframework.core.io.support.PropertiesLoaderUtils
                .loadAllProperties("application.properties");
        java.util.Map<String, String> env = java.util.Map.of(
                "TerrakubeProviderMirrorEnabled", "true",
                "TerrakubeProviderMirrorOnly", "true",
                "TerrakubeProviderMirrorRegistryUrl", "https://registry.example.com");
        org.springframework.util.PropertyPlaceholderHelper helper =
                new org.springframework.util.PropertyPlaceholderHelper("${", "}", ":", null, true);
        String[] resolved = java.util.stream.Stream.of("enabled", "only", "registry-url")
                .map(key -> {
                    String name = "io.terrakube.provider-mirror." + key;
                    assertThat(file.getProperty(name)).as(name).isNotNull();
                    return name + "=" + helper.replacePlaceholders(file.getProperty(name), env::get);
                })
                .toArray(String[]::new);

        contextRunner.withPropertyValues(resolved).run(context -> {
            ProviderMirrorProperties props = context.getBean(ProviderMirrorProperties.class);
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.isOnly()).isTrue();
            assertThat(props.getRegistryUrl()).isEqualTo("https://registry.example.com");
        });
    }

    @Test
    void theEnvironmentVariableDefaultsKeepTheMirrorOff() throws Exception {
        java.util.Properties file = org.springframework.core.io.support.PropertiesLoaderUtils
                .loadAllProperties("application.properties");
        org.springframework.util.PropertyPlaceholderHelper helper =
                new org.springframework.util.PropertyPlaceholderHelper("${", "}", ":", null, true);
        assertThat(helper.replacePlaceholders(file.getProperty("io.terrakube.provider-mirror.enabled"), n -> null))
                .isEqualTo("false");
        assertThat(helper.replacePlaceholders(file.getProperty("io.terrakube.provider-mirror.only"), n -> null))
                .isEqualTo("false");
    }
}
