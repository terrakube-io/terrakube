package io.terrakube.executor.service.terraform.cache;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BinaryCacheRecoveryService has two constructors (a two-arg production one and a package-private
 * three-arg one for tests). Without an explicit hint, Spring can't pick between them and tries a
 * no-arg constructor that doesn't exist, failing executor startup entirely - this proves Spring
 * actually resolves the real bean through its container, not just that the class compiles.
 */
class BinaryCacheRecoveryServiceWiringTest {

    @Configuration
    static class TestConfiguration {
        @Bean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
        @Bean TerraformState terraformState() { return Mockito.mock(TerraformState.class); }
    }

    @Test
    void springResolvesTheTwoArgumentConstructor() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfiguration.class)
                .withBean(BinaryCacheRecoveryService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(BinaryCacheRecoveryService.class);
                });
    }
}
