package io.terrakube.executor.service.hardened;

import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.configuration.RedisAutoConfiguration;
import io.terrakube.executor.configuration.RedisProperties;
import io.terrakube.executor.configuration.security.InternalSecretLoader;
import io.terrakube.executor.plugin.tfstate.api.RunnerApiClient;
import io.terrakube.executor.plugin.tfstate.api.RunnerApiClientConfiguration;
import io.terrakube.executor.plugin.tfstate.configuration.TerraformStateProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class HardenedWiringTest {

    @Configuration
    @EnableConfigurationProperties({ExecutorFlagsProperties.class, TerraformStateProperties.class})
    static class Base {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Base.class, RedisAutoConfiguration.class, RedisProperties.class,
                    RunnerApiClientConfiguration.class, InternalSecretLoader.class, HardenedRunnerGuard.class)
            .withPropertyValues("io.terrakube.api.url=https://api.example",
                    "io.terrakube.executor.redis.hostname=redis", "io.terrakube.executor.redis.port=${TerrakubeRedisPort}");

    @Test
    void hardenedRunnerStartsWithoutAnyRedisConfiguration() {
        runner.withPropertyValues("io.terrakube.executor.flags.hardened=true", "io.terrakube.executor.flags.ephemeral=true",
                        "io.terrakube.executor.plugin.tfstate.type=ApiTerraformStateImpl")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RedisProperties.class);
                    assertThat(context).doesNotHaveBean(RedisAutoConfiguration.class);
                    assertThat(context).hasSingleBean(RunnerApiClient.class);
                    assertThat(context).hasSingleBean(HardenedRunnerGuard.class);
                });
    }

    @Test
    void persistentHardenedExecutorRefusesToStart() {
        runner.withPropertyValues("io.terrakube.executor.flags.hardened=true", "io.terrakube.executor.flags.ephemeral=false",
                        "io.terrakube.executor.plugin.tfstate.type=ApiTerraformStateImpl")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void legacyExecutorKeepsItsRedisWiring() {
        runner.withPropertyValues("io.terrakube.executor.redis.port=6379", "io.terrakube.executor.plugin.tfstate.type=AwsTerraformStateImpl")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RedisProperties.class);
                    assertThat(context).doesNotHaveBean(RunnerApiClient.class);
                    assertThat(context).hasSingleBean(HardenedRunnerGuard.class);
                });
    }
}
