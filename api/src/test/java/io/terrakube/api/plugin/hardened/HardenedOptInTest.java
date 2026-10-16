package io.terrakube.api.plugin.hardened;

import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HardenedOptInTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RunnerJobGuard.class, RunnerChangePolicy.class, RunnerSourceService.class,
                    RunnerStateController.class, RunnerLockAdminController.class)
            .withBean(JobRepository.class, () -> mock(JobRepository.class))
            .withBean(StepRepository.class, () -> mock(StepRepository.class))
            .withPropertyValues("io.terrakube.hostname=api.example");

    @Test
    void nothingFromHardenedModeIsRegisteredByDefault() {
        runner.run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(RunnerJobGuard.class)
                .doesNotHaveBean(RunnerChangePolicy.class)
                .doesNotHaveBean(RunnerSourceService.class)
                .doesNotHaveBean(RunnerStateController.class)
                .doesNotHaveBean(RunnerLockAdminController.class));
        runner.withPropertyValues("io.terrakube.hardened.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(RunnerJobGuard.class));
    }

    @Test
    void theRunnerPolicyIsRegisteredWhenHardenedModeIsEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(RunnerJobGuard.class, RunnerChangePolicy.class)
                .withBean(JobRepository.class, () -> mock(JobRepository.class))
                .withBean(StepRepository.class, () -> mock(StepRepository.class))
                .withPropertyValues("io.terrakube.hostname=api.example", "io.terrakube.hardened.enabled=true")
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(RunnerJobGuard.class)
                        .hasSingleBean(RunnerChangePolicy.class));
    }
}
