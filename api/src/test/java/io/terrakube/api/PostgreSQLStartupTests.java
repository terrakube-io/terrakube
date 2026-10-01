package io.terrakube.api;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class PostgreSQLStartupTests {
    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("terrakube")
            .withUsername("terrakube")
            .withPassword("terrakube");

    @Test
    void contextLoads() {
        assertTrue(postgreSQLContainer.isRunning());
    }

    @Test
    void migratesLegacyWorkspaceStatusOrdinalToEnumName() throws Exception {
        try (Connection connection = connectToPostgres()) {
            applyChangelog(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO organization (id, name) VALUES (?, ?)") ) {
                statement.setString(1, "migration-test-organization");
                statement.setString(2, "migration-test-organization");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO workspace (id, name, source, branch, terraform_version, organization_id, last_job_status)
                    VALUES (?, ?, ?, ?, ?, ?, '5')
                    """)) {
                statement.setString(1, "migration-test-workspace");
                statement.setString(2, "migration-test-workspace");
                statement.setString(3, "https://example.com/migration-test.git");
                statement.setString(4, "main");
                statement.setString(5, "1.0.0");
                statement.setString(6, "migration-test-organization");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM databasechangelog WHERE id = ? AND author = ?")) {
                statement.setString(1, "2-34-0-workspace-last-status-enum");
                statement.setString(2, "terrakube");
                statement.executeUpdate();
            }

            applyChangelog(connection);

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT last_job_status FROM workspace WHERE id = ?")) {
                statement.setString(1, "migration-test-workspace");
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("completed", result.getString(1));
                }
            }
        }
    }

    private Connection connectToPostgres() throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        SQLException lastFailure;
        do {
            try {
                return DriverManager.getConnection(
                        postgreSQLContainer.getJdbcUrl(), postgreSQLContainer.getUsername(), postgreSQLContainer.getPassword());
            } catch (SQLException exception) {
                lastFailure = exception;
                Thread.sleep(200);
            }
        } while (System.nanoTime() < deadline);
        throw lastFailure;
    }

    private void applyChangelog(Connection connection) throws Exception {
        Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        new Liquibase("db/changelog/changelog.xml", new ClassLoaderResourceAccessor(), database)
                .update(new Contexts(), new LabelExpression());
    }
}
