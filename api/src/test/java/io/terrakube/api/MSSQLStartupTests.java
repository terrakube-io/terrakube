package io.terrakube.api;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
@Testcontainers
class MSSQLStartupTests {

    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    @Container
    private static final MSSQLServerContainer<?> mssqlServerContainer = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
            .acceptLicense();

    @DynamicPropertySource
    static void registerMSSQLProperties(DynamicPropertyRegistry registry) {
        String host = mssqlServerContainer.getHost();
        Integer port = mssqlServerContainer.getMappedPort(1433);
        String user = mssqlServerContainer.getUsername();
        String password = mssqlServerContainer.getPassword();
        String databaseName = mssqlServerContainer.getDatabaseName();

        registry.add("io.terrakube.api.plugin.datasource.hostname", () -> host);
        registry.add("io.terrakube.api.plugin.datasource.databasePort", () -> port.toString());
        registry.add("io.terrakube.api.plugin.datasource.databaseUser", () -> user);
        registry.add("io.terrakube.api.plugin.datasource.databaseName", () -> databaseName);
        registry.add("io.terrakube.api.plugin.datasource.databasePassword", () -> password);
        registry.add("spring.liquibase.liquibase-schema", () -> "dbo");
    }

    @Test
    void contextLoads() {
        assertTrue(mssqlServerContainer.isRunning());
    }
}
