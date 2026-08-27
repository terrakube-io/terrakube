package io.terrakube.api.plugin.scheduler.module;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalModuleVersionMigrationTest {

    private Connection connection;
    private Database database;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:canonical-migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE module (id VARCHAR(36) PRIMARY KEY, provider VARCHAR(64), tag_prefix VARCHAR(64), latest_version VARCHAR(64))");
            statement.execute("CREATE TABLE module_version (id VARCHAR(36) PRIMARY KEY, module_id VARCHAR(36), version VARCHAR(256), git_tag VARCHAR(256))");
        }
        database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void canonicalisesVersionsAndAliasesKnownSystem() throws Exception {
        String moduleId = insertModule("aws-ecs", null);
        insertVersion(moduleId, "v2.0.1", null);

        new CanonicalModuleVersionMigration().execute(database);

        assertThat(providerOf(moduleId)).isEqualTo("aws");
        assertThat(latestVersionOf(moduleId)).isEqualTo("2.0.1");
        assertThat(versionRowsOf(moduleId)).containsExactly("2.0.1|v2.0.1");
    }

    @Test
    void skipsModuleOnCollisionWithoutModifyingIt() throws Exception {
        String moduleId = insertModule("aws", null);
        insertVersion(moduleId, "v2.0.1", null);
        insertVersion(moduleId, "2.0.1", null);

        new CanonicalModuleVersionMigration().execute(database);

        assertThat(versionRowsOf(moduleId)).containsExactlyInAnyOrder("v2.0.1|null", "2.0.1|null");
        assertThat(latestVersionOf(moduleId)).isNull();
    }

    @Test
    void skipsModuleWithUnresolvableSystem() throws Exception {
        String moduleId = insertModule("aws ecs!", null);
        insertVersion(moduleId, "v1.0.0", null);

        new CanonicalModuleVersionMigration().execute(database);

        assertThat(providerOf(moduleId)).isEqualTo("aws ecs!");
        assertThat(versionRowsOf(moduleId)).containsExactly("v1.0.0|null");
    }

    @Test
    void respectsConfiguredTagPrefix() throws Exception {
        String moduleId = insertModule("aws", "module-");
        insertVersion(moduleId, "module-v2.0.1", null);

        new CanonicalModuleVersionMigration().execute(database);

        assertThat(versionRowsOf(moduleId)).containsExactly("2.0.1|module-v2.0.1");
    }

    private String insertModule(String provider, String tagPrefix) throws Exception {
        String id = UUID.randomUUID().toString();
        try (var statement = connection.prepareStatement(
                "INSERT INTO module (id, provider, tag_prefix) VALUES (?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, provider);
            statement.setString(3, tagPrefix);
            statement.executeUpdate();
        }
        connection.commit();
        return id;
    }

    private void insertVersion(String moduleId, String version, String gitTag) throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO module_version (id, module_id, version, git_tag) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, moduleId);
            statement.setString(3, version);
            statement.setString(4, gitTag);
            statement.executeUpdate();
        }
        connection.commit();
    }

    private String providerOf(String moduleId) throws Exception {
        try (var statement = connection.prepareStatement("SELECT provider FROM module WHERE id = ?")) {
            statement.setString(1, moduleId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private String latestVersionOf(String moduleId) throws Exception {
        try (var statement = connection.prepareStatement("SELECT latest_version FROM module WHERE id = ?")) {
            statement.setString(1, moduleId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private java.util.List<String> versionRowsOf(String moduleId) throws Exception {
        java.util.List<String> rows = new java.util.ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT version, git_tag FROM module_version WHERE module_id = ? ORDER BY version")) {
            statement.setString(1, moduleId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1) + "|" + rs.getString(2));
                }
            }
        }
        return rows;
    }
}
