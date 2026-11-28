package io.terrakube.api.plugin.datasource;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PostgresAzureAdAuthDataSourceTest {

    private static final String ENTRA_TOKEN = "entra-access-token";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("terrakube")
            .withUsername("terrakube")
            .withPassword(ENTRA_TOKEN);

    @Test
    void requestsTokensForTheAzurePostgresqlScope() throws SQLException {
        RecordingCredential credential = new RecordingCredential(token("token-1", Duration.ofHours(1)));

        assertEquals("token-1", new PostgresAzureAdAuthDataSource(credential, CLOCK).getAccessToken(false));
        assertEquals(List.of(List.of(PostgresAzureAdAuthDataSource.AZURE_POSTGRESQL_SCOPE)), credential.requestedScopes);
    }

    @Test
    void reusesTheTokenUntilItIsAboutToExpire() throws SQLException {
        RecordingCredential credential = new RecordingCredential(
                token("token-1", Duration.ofHours(1)),
                token("token-2", Duration.ofHours(1)));
        MutableClock clock = new MutableClock(NOW);
        PostgresAzureAdAuthDataSource dataSource = new PostgresAzureAdAuthDataSource(credential, clock);

        assertEquals("token-1", dataSource.getAccessToken(false));
        clock.now = NOW.plus(Duration.ofMinutes(54));
        assertEquals("token-1", dataSource.getAccessToken(false));
        clock.now = NOW.plus(Duration.ofHours(1)).minus(PostgresAzureAdAuthDataSource.TOKEN_REFRESH_MARGIN);
        assertEquals("token-2", dataSource.getAccessToken(false));
        assertEquals(2, credential.requestedScopes.size());
    }

    @Test
    void forcedRefreshRequestsANewToken() throws SQLException {
        RecordingCredential credential = new RecordingCredential(
                token("token-1", Duration.ofHours(1)),
                token("token-2", Duration.ofHours(1)));
        PostgresAzureAdAuthDataSource dataSource = new PostgresAzureAdAuthDataSource(credential, CLOCK);

        assertEquals("token-1", dataSource.getAccessToken(false));
        assertEquals("token-2", dataSource.getAccessToken(true));
    }

    @Test
    void credentialFailureIsReportedAsSqlException() {
        TokenCredential credential = request -> Mono.error(new IllegalStateException("no managed identity endpoint"));
        PostgresAzureAdAuthDataSource dataSource = new PostgresAzureAdAuthDataSource(credential, CLOCK);

        SQLException e = assertThrows(SQLException.class, () -> dataSource.getAccessToken(false));
        assertTrue(e.getMessage().contains("Microsoft Entra ID access token"), e.getMessage());
        assertTrue(e.getMessage().contains("no managed identity endpoint"), e.getMessage());
    }

    @Test
    void missingTokenIsReportedAsSqlException() {
        PostgresAzureAdAuthDataSource dataSource = new PostgresAzureAdAuthDataSource(request -> Mono.empty(), CLOCK);

        SQLException e = assertThrows(SQLException.class, () -> dataSource.getAccessToken(false));
        assertTrue(e.getMessage().contains("no token was returned"), e.getMessage());
    }

    @Test
    void connectsWithTheAccessTokenInsteadOfTheConfiguredPassword() throws SQLException {
        PostgresAzureAdAuthDataSource dataSource = dataSource(new RecordingCredential(token(ENTRA_TOKEN, Duration.ofHours(1))));
        dataSource.setPassword("static-password-is-ignored");

        assertConnects(dataSource);
    }

    @Test
    void rejectedTokenIsRefreshedAndTheConnectionRetried() throws SQLException {
        RecordingCredential credential = new RecordingCredential(
                token("revoked-token", Duration.ofHours(1)),
                token(ENTRA_TOKEN, Duration.ofHours(1)));

        assertConnects(dataSource(credential));
        assertEquals(2, credential.requestedScopes.size());
    }

    @Test
    void connectionFailuresUnrelatedToAuthenticationAreNotRetried() {
        RecordingCredential credential = new RecordingCredential(token(ENTRA_TOKEN, Duration.ofHours(1)));
        PostgresAzureAdAuthDataSource dataSource = dataSource(credential);
        dataSource.setDatabaseName("missing_database");

        SQLException e = assertThrows(SQLException.class, dataSource::getConnection);
        assertEquals("3D000", e.getSQLState());
        assertEquals(1, credential.requestedScopes.size());
    }

    private static PostgresAzureAdAuthDataSource dataSource(TokenCredential credential) {
        PostgresAzureAdAuthDataSource dataSource = new PostgresAzureAdAuthDataSource(credential, CLOCK);
        dataSource.setServerNames(new String[]{postgreSQLContainer.getHost()});
        dataSource.setPortNumbers(new int[]{postgreSQLContainer.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)});
        dataSource.setDatabaseName(postgreSQLContainer.getDatabaseName());
        dataSource.setUser(postgreSQLContainer.getUsername());
        return dataSource;
    }

    private static void assertConnects(PostgresAzureAdAuthDataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT current_user")) {
            assertTrue(result.next());
            assertEquals(postgreSQLContainer.getUsername(), result.getString(1));
        }
    }

    private static AccessToken token(String value, Duration validFor) {
        return new AccessToken(value, OffsetDateTime.ofInstant(NOW.plus(validFor), ZoneOffset.UTC));
    }

    private static final class RecordingCredential implements TokenCredential {
        private final Iterator<AccessToken> tokens;
        private final List<List<String>> requestedScopes = new ArrayList<>();

        private RecordingCredential(AccessToken... tokens) {
            this.tokens = List.of(tokens).iterator();
        }

        @Override
        public Mono<AccessToken> getToken(TokenRequestContext request) {
            requestedScopes.add(List.copyOf(request.getScopes()));
            return Mono.just(tokens.next());
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
