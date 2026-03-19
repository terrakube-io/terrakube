package io.terrakube.api;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
@Testcontainers
class PostgreSQLStartupTests {

    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("terrakube")
            .withUsername("terrakube")
            .withPassword("terrakube");

    @DynamicPropertySource
    static void registerPostgreSQLProperties(DynamicPropertyRegistry registry) {
        registry.add("io.terrakube.api.plugin.datasource.type", () -> "POSTGRESQL");
        registry.add("io.terrakube.api.plugin.datasource.hostname", postgreSQLContainer::getHost);
        registry.add("io.terrakube.api.plugin.datasource.databasePort", () -> postgreSQLContainer.getMappedPort(5432).toString());
        registry.add("io.terrakube.api.plugin.datasource.databaseName", postgreSQLContainer::getDatabaseName);
        registry.add("io.terrakube.api.plugin.datasource.databaseUser", postgreSQLContainer::getUsername);
        registry.add("io.terrakube.api.plugin.datasource.databasePassword", postgreSQLContainer::getPassword);
    }

    @Test
    void contextLoads() {
        assertTrue(postgreSQLContainer.isRunning());
    }
}
