package io.terrakube.api;

import io.terrakube.api.rs.project.ProjectSummary;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.hibernate.annotations.Subselect;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Own container: PostgreSQLStartupTests re-runs individual changesets and must not share state with this test.
@Testcontainers
class ProjectSummaryPostgresTests {
    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("terrakube")
            .withUsername("terrakube")
            .withPassword("terrakube");

    @Test
    void projectSummaryAggregateRunsAfterMigrationsOnPostgres() throws Exception {
        try (Connection connection = connectToPostgres()) {
            applyChangelog(connection);
            connection.createStatement().executeUpdate(
                    "INSERT INTO organization (id, name) VALUES ('summary-org', 'summary-org')");
            connection.createStatement().executeUpdate(
                    "INSERT INTO project (id, name, organization_id) VALUES ('summary-project', 'summary', 'summary-org')");
            connection.createStatement().executeUpdate(
                    "INSERT INTO project (id, name, organization_id) VALUES ('empty-project', 'empty', 'summary-org')");
            String[][] workspaces = {
                    {"completed", "COMPLIANT", "false", "false"},
                    {"failed", "COMPLIANT", "false", "false"},
                    {"waitingApproval", "NON_COMPLIANT", "false", "false"},
                    {"running", "COMPLIANT", "true", "false"},
                    {"queue", "UNKNOWN", "false", "false"},
                    {"NeverExecuted", "UNKNOWN", "false", "false"},
                    // soft-deleted, must not be counted
                    {"failed", "NON_COMPLIANT", "false", "true"},
            };
            for (int i = 0; i < workspaces.length; i++) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO workspace (id, name, source, branch, terraform_version, organization_id, project_id,
                                               last_job_status, policy_compliance_status, locked, deleted, last_job_date)
                        VALUES (?, ?, 'https://example.com/r.git', 'main', '1.0.0', 'summary-org', 'summary-project',
                                ?, ?, CAST(? AS boolean), CAST(? AS boolean), now())
                        """)) {
                    statement.setString(1, "summary-ws-" + i);
                    statement.setString(2, "summary-ws-" + i);
                    statement.setString(3, workspaces[i][0]);
                    statement.setString(4, workspaces[i][1]);
                    statement.setString(5, workspaces[i][2]);
                    statement.setString(6, workspaces[i][3]);
                    statement.executeUpdate();
                }
            }
            connection.createStatement().executeUpdate(
                    "INSERT INTO project_access (id, name, project_id, role) VALUES ('pa-1', 'team-a', 'summary-project', 'read')");

            // Run the exact SQL the entity uses, so the test cannot drift from the mapping.
            String subselect = ProjectSummary.class.getAnnotation(Subselect.class).value();
            try (ResultSet rows = connection.createStatement().executeQuery(
                    "SELECT id, workspace_count, completed_count, failed_count, waiting_approval_count, running_count, "
                            + "queued_count, never_run_count, non_compliant_count, compliant_count, exempted_count, queued_job_count, queued_since, locked_count, attention_count, "
                            + "team_count, last_run_at FROM (" + subselect + ") s ORDER BY attention_count DESC, name")) {
                assertTrue(rows.next());
                assertEquals("summary-project", rows.getString("id"));
                assertEquals(6, rows.getLong("workspace_count"));
                assertEquals(1, rows.getLong("completed_count"));
                assertEquals(1, rows.getLong("failed_count"));
                assertEquals(1, rows.getLong("waiting_approval_count"));
                assertEquals(1, rows.getLong("running_count"));
                assertEquals(1, rows.getLong("queued_count"));
                assertEquals(1, rows.getLong("never_run_count"));
                assertEquals(1, rows.getLong("non_compliant_count"));
                assertEquals(1, rows.getLong("locked_count"));
                assertEquals(2, rows.getLong("attention_count"));
                assertEquals(1, rows.getLong("team_count"));
                assertNotNull(rows.getTimestamp("last_run_at"));

                assertTrue(rows.next());
                assertEquals("empty-project", rows.getString("id"));
                assertEquals(0, rows.getLong("workspace_count"));
                assertEquals(0, rows.getLong("attention_count"));
                assertNull(rows.getTimestamp("last_run_at"));
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
