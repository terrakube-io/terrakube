package io.terrakube.api;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The full {@link ServerApplicationTests} harness, booted on Postgres 16 with the production changelog instead of H2
 * and the demo data, for tests whose output depends on the schema Postgres actually ends up with.
 */
public abstract class PostgresServerApplicationTests extends ServerApplicationTests {

    // One container per JVM: Spring caches the context across subclasses, so a per-class container would leave later
    // classes holding a pool to a stopped database.
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("terrakube")
            .withUsername("terrakube")
            .withPassword("terrakube")
            // a Docker API connection pooled before an idle gap can be dead (podman drops them); a retry opens a new one
            .withStartupAttempts(2);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("io.terrakube.api.plugin.datasource.type", () -> "POSTGRESQL");
        registry.add("io.terrakube.api.plugin.datasource.hostname", POSTGRES::getHost);
        registry.add("io.terrakube.api.plugin.datasource.databasePort",
                () -> String.valueOf(POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)));
        registry.add("io.terrakube.api.plugin.datasource.databaseName", POSTGRES::getDatabaseName);
        registry.add("io.terrakube.api.plugin.datasource.databaseUser", POSTGRES::getUsername);
        registry.add("io.terrakube.api.plugin.datasource.databasePassword", POSTGRES::getPassword);
        registry.add("io.terrakube.api.plugin.datasource.databaseSchema", () -> "public");
        registry.add("io.terrakube.api.plugin.datasource.sslMode", () -> "disable");
        registry.add("spring.liquibase.change-log", () -> "classpath:/db/changelog/changelog.xml");
    }
}
