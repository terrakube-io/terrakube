package io.terrakube.api.plugin.datasource;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredentialBuilder;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Connects to Azure Database for PostgreSQL with a short-lived Microsoft Entra ID access token instead of a
 * static password. The token comes from {@code DefaultAzureCredential}, so AKS Workload Identity, managed
 * identities and service principals all work without storing a database password.
 */
@Slf4j
public class PostgresAzureAdAuthDataSource extends PGSimpleDataSource {

    static final String AZURE_POSTGRESQL_SCOPE = "https://ossrdbms-aad.database.windows.net/.default";
    static final Duration TOKEN_REFRESH_MARGIN = Duration.ofMinutes(5);
    private static final Duration TOKEN_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String INVALID_AUTHORIZATION_SQL_STATE_CLASS = "28";

    private final transient TokenCredential tokenCredential;
    private final transient Clock clock;
    private transient AccessToken accessToken;

    public PostgresAzureAdAuthDataSource(String azureClientId) {
        this(defaultAzureCredential(azureClientId), Clock.systemUTC());
    }

    PostgresAzureAdAuthDataSource(TokenCredential tokenCredential, Clock clock) {
        this.tokenCredential = tokenCredential;
        this.clock = clock;
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
        try {
            return super.getConnection(user, getAccessToken(false));
        } catch (SQLException e) {
            if (e.getSQLState() == null || !e.getSQLState().startsWith(INVALID_AUTHORIZATION_SQL_STATE_CLASS)) {
                throw e;
            }
            log.warn("PostgreSQL rejected the Microsoft Entra ID access token, requesting a new token and retrying: {}", e.getMessage());
            return super.getConnection(user, getAccessToken(true));
        }
    }

    synchronized String getAccessToken(boolean forceRefresh) throws SQLException {
        if (forceRefresh || accessToken == null || isExpiring(accessToken)) {
            accessToken = requestAccessToken();
            log.debug("Got a Microsoft Entra ID access token for PostgreSQL that expires at {}", accessToken.getExpiresAt());
        }
        return accessToken.getToken();
    }

    private boolean isExpiring(AccessToken token) {
        return token.getExpiresAt() == null
                || !OffsetDateTime.now(clock).plus(TOKEN_REFRESH_MARGIN).isBefore(token.getExpiresAt());
    }

    private AccessToken requestAccessToken() throws SQLException {
        AccessToken token;
        try {
            token = tokenCredential
                    .getToken(new TokenRequestContext().addScopes(AZURE_POSTGRESQL_SCOPE))
                    .block(TOKEN_REQUEST_TIMEOUT);
        } catch (RuntimeException e) {
            throw new SQLException("Unable to get a Microsoft Entra ID access token for PostgreSQL: " + e.getMessage(), e);
        }
        if (token == null || token.getToken() == null || token.getToken().isBlank()) {
            throw new SQLException("Unable to get a Microsoft Entra ID access token for PostgreSQL: no token was returned");
        }
        return token;
    }

    private static TokenCredential defaultAzureCredential(String azureClientId) {
        DefaultAzureCredentialBuilder builder = new DefaultAzureCredentialBuilder();
        if (azureClientId != null && !azureClientId.isBlank()) {
            builder.managedIdentityClientId(azureClientId)
                    .workloadIdentityClientId(azureClientId);
        }
        return builder.build();
    }
}
