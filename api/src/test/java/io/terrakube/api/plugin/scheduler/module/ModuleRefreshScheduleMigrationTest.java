package io.terrakube.api.plugin.scheduler.module;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleRefreshScheduleMigrationTest {

    private Connection connection;
    private Database database;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:module-refresh-schedule-" + UUID.randomUUID());
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE module (id VARCHAR(36) PRIMARY KEY, name VARCHAR(255))");
            statement.execute("INSERT INTO module (id, name) VALUES ('existing', 'network')");
        }
        database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void existingModulesAreDueNowAndCountAsRecentlyTagged() throws Exception {
        new Liquibase("db/changelog/local/changelog-2.35.0-module-refresh-schedule.xml",
                new ClassLoaderResourceAccessor(), database).update(new Contexts());

        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO module (id, name) VALUES ('created-later', 'iam')");
            try (ResultSet rs = statement.executeQuery("SELECT id, next_refresh, last_refresh, last_tag_detected_at, "
                    + "last_refresh_error FROM module ORDER BY id DESC")) {
                rs.next();
                assertThat(rs.getString("id")).isEqualTo("existing");
                assertThat(rs.getTimestamp("next_refresh")).isNull();
                assertThat(rs.getTimestamp("last_refresh")).isNull();
                assertThat(rs.getTimestamp("last_tag_detected_at")).isNotNull();
                assertThat(rs.getString("last_refresh_error")).isNull();

                rs.next();
                assertThat(rs.getString("id")).isEqualTo("created-later");
                assertThat(rs.getTimestamp("last_tag_detected_at")).isNull();
            }
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                    + "WHERE INDEX_NAME = 'IDX_MODULE_NEXT_REFRESH'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }
}
