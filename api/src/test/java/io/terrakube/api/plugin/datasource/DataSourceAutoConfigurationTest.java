package io.terrakube.api.plugin.datasource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DataSourceAutoConfigurationTest {

    @Test
    void awsIamAndEntraIdAuthenticationCannotBothBeEnabled() {
        DataSourceConfigurationProperties properties = new DataSourceConfigurationProperties();
        properties.setType(DataSourceType.POSTGRESQL);
        properties.setHostname("localhost");
        properties.setDatabasePort("5432");
        properties.setAwsIamAuth(true);
        properties.setAzureAdAuth(true);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new DataSourceAutoConfiguration().getDataSource(properties, new SimpleMeterRegistry()));
        assertTrue(e.getMessage().contains("cannot both be enabled"), e.getMessage());
    }
}
