package io.terrakube.api.plugin.scheduler.provider;

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
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderTrustSignatureMigrationTest {

    private Connection connection;
    private Database database;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:provider-signature-" + UUID.randomUUID());
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE implementation (trust_signature VARCHAR(32))");
        }
        database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void widensTrustSignatureColumn() throws Exception {
        Liquibase liquibase = new Liquibase(
                "db/changelog/local/changelog-2.33.0-provider-trust-signature.xml",
                new ClassLoaderResourceAccessor(),
                database
        );
        liquibase.update(new Contexts());

        String trustSignature = "a".repeat(128);
        try (var statement = connection.prepareStatement("INSERT INTO implementation (trust_signature) VALUES (?)")) {
            statement.setString(1, trustSignature);
            statement.executeUpdate();
        }
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT trust_signature FROM implementation")) {
            result.next();
            assertThat(result.getString(1)).isEqualTo(trustSignature);
        }
    }
}
