package io.terrakube.api.plugin.scheduler;

import org.junit.jupiter.api.Test;

import io.terrakube.api.plugin.datasource.DataSourceConfigurationProperties;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchedulerThreadPoolValidationTest {

    private DataSourceConfigurationProperties propertiesWithPoolSize(int poolSize) {
        DataSourceConfigurationProperties properties = new DataSourceConfigurationProperties();
        properties.setPoolSize(poolSize);
        return properties;
    }

    @Test
    void acceptsAThreadCountBelowThePoolSize() {
        assertThatCode(() -> new SchedulerThreadPoolValidation(8, propertiesWithPoolSize(10)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAThreadCountEqualToThePoolSize() {
        assertThatThrownBy(() -> new SchedulerThreadPoolValidation(10, propertiesWithPoolSize(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be less than");
    }

    @Test
    void rejectsAThreadCountAboveThePoolSize() {
        assertThatThrownBy(() -> new SchedulerThreadPoolValidation(20, propertiesWithPoolSize(10)))
                .isInstanceOf(IllegalStateException.class);
    }
}
