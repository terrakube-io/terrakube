package io.terrakube.api.plugin.vcs.provider.github;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.SchedulerException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.terrakube.api.plugin.scheduler.ScheduleGitHubAppTokenService;
import io.terrakube.api.repository.GitHubAppTokenRepository;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.rs.vcs.GitHubAppToken;
import io.terrakube.api.rs.vcs.Vcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class GitHubTokenServiceTest {

    private static final String OWNER = "testowner";
    private static final String REPO = "testrepo";
    private static final String INSTALLATION_ID = "98765";
    private static final String APP_ID = "app-client-id";

    private GitHubAppTokenRepository gitHubAppTokenRepository;
    private VcsRepository vcsRepository;
    private ScheduleGitHubAppTokenService scheduleGitHubAppTokenService;
    private GitHubTokenService subject;

    private HttpServer httpServer;
    private String privateKeyPem;
    private AtomicInteger accessTokenHits;
    private AtomicInteger installationHits;
    private String tokenToReturn;
    private String expiresAtToReturn;
    private boolean includeExpiresAt;

    @BeforeEach
    public void setup() throws Exception {
        gitHubAppTokenRepository = mock(GitHubAppTokenRepository.class);
        vcsRepository = mock(VcsRepository.class);
        scheduleGitHubAppTokenService = mock(ScheduleGitHubAppTokenService.class);

        subject = new GitHubTokenService();
        subject.objectMapper = new ObjectMapper();
        subject.gitHubAppTokenRepository = gitHubAppTokenRepository;
        subject.vcsRepository = vcsRepository;
        subject.scheduleGitHubAppTokenService = scheduleGitHubAppTokenService;

        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";

        accessTokenHits = new AtomicInteger(0);
        installationHits = new AtomicInteger(0);
        tokenToReturn = "ghs_refreshed-token";
        expiresAtToReturn = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        includeExpiresAt = true;

        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/repos/" + OWNER + "/" + REPO + "/installation", exchange -> {
            installationHits.incrementAndGet();
            writeJson(exchange, 200, "{\"id\":\"" + INSTALLATION_ID + "\"}");
        });
        httpServer.createContext("/app/installations/" + INSTALLATION_ID + "/access_tokens", exchange -> {
            accessTokenHits.incrementAndGet();
            String body = includeExpiresAt
                    ? "{\"token\":\"" + tokenToReturn + "\",\"expires_at\":\"" + expiresAtToReturn + "\"}"
                    : "{\"token\":\"" + tokenToReturn + "\"}";
            writeJson(exchange, 201, body);
        });
        httpServer.start();
    }

    @AfterEach
    public void tearDown() {
        httpServer.stop(0);
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private Vcs createVcs() {
        Vcs vcs = new Vcs();
        vcs.setId(UUID.randomUUID());
        vcs.setClientId(APP_ID);
        vcs.setPrivateKey(privateKeyPem);
        vcs.setApiUrl("http://localhost:" + httpServer.getAddress().getPort());
        return vcs;
    }

    private GitHubAppToken createCachedToken(Instant expiresAt) {
        GitHubAppToken token = new GitHubAppToken();
        token.setId(UUID.randomUUID());
        token.setAppId(APP_ID);
        token.setOwner(OWNER);
        token.setInstallationId(INSTALLATION_ID);
        token.setToken("ghs_stale-token");
        token.setExpiresAt(expiresAt);
        return token;
    }

    // Issue 1: cached token past expiry must be treated as a miss and refreshed in place,
    // not served as-is.
    @Test
    public void getGitHubAppToken_expiredCachedToken_refetchesAndUpdatesInPlace() throws Exception {
        Vcs vcs = createVcs();
        GitHubAppToken cached = createCachedToken(Instant.now().minus(5, ChronoUnit.MINUTES));

        when(gitHubAppTokenRepository.findByAppIdAndOwner(APP_ID, OWNER)).thenReturn(cached);
        when(gitHubAppTokenRepository.save(any(GitHubAppToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GitHubAppToken result = subject.getGitHubAppToken(vcs, new String[] { OWNER, REPO });

        assertEquals(tokenToReturn, result.getToken());
        assertEquals(cached.getId(), result.getId(), "must update the existing row, not create a new one");
        assertEquals(0, installationHits.get(), "should not re-discover the installation, it is already known");
        assertEquals(1, accessTokenHits.get());
        verify(gitHubAppTokenRepository, times(1)).save(any(GitHubAppToken.class));
    }

    // A token with no expiry recorded (e.g. rows created before this fix shipped) must be
    // treated the same as expired, so it self-heals on the next read instead of being served forever.
    @Test
    public void getGitHubAppToken_missingExpiry_treatedAsExpired() throws Exception {
        Vcs vcs = createVcs();
        GitHubAppToken cached = createCachedToken(null);

        when(gitHubAppTokenRepository.findByAppIdAndOwner(APP_ID, OWNER)).thenReturn(cached);
        when(gitHubAppTokenRepository.save(any(GitHubAppToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GitHubAppToken result = subject.getGitHubAppToken(vcs, new String[] { OWNER, REPO });

        assertEquals(tokenToReturn, result.getToken());
        assertEquals(1, accessTokenHits.get());
    }

    @Test
    public void getGitHubAppToken_validCachedToken_isServedWithoutAnyHttpCall() throws Exception {
        Vcs vcs = createVcs();
        GitHubAppToken cached = createCachedToken(Instant.now().plus(30, ChronoUnit.MINUTES));

        when(gitHubAppTokenRepository.findByAppIdAndOwner(APP_ID, OWNER)).thenReturn(cached);

        GitHubAppToken result = subject.getGitHubAppToken(vcs, new String[] { OWNER, REPO });

        assertEquals("ghs_stale-token", result.getToken());
        assertEquals(0, accessTokenHits.get());
        assertEquals(0, installationHits.get());
        verify(gitHubAppTokenRepository, never()).save(any(GitHubAppToken.class));
    }

    // Issue 2: if the Vcs backing a cached token has been deleted (or recreated with a
    // different row), refreshAccessToken must not NPE - it should clean up the orphaned
    // row and its refresh schedule instead of leaving a stale token behind forever.
    @Test
    public void refreshAccessToken_noMatchingVcs_deletesOrphanedTokenAndSchedule() throws Exception {
        GitHubAppToken orphaned = createCachedToken(Instant.now().minus(1, ChronoUnit.HOURS));
        when(vcsRepository.findFirstByClientId(APP_ID)).thenReturn(null);

        String result = subject.refreshAccessToken(orphaned);

        assertNull(result);
        verify(scheduleGitHubAppTokenService, times(1)).deleteTask(orphaned.getId().toString());
        verify(gitHubAppTokenRepository, times(1)).delete(orphaned);
        assertEquals(0, accessTokenHits.get());
    }

    @Test
    public void refreshAccessToken_schedulerFailureDuringCleanup_stillDeletesOrphanedRow() throws Exception {
        GitHubAppToken orphaned = createCachedToken(Instant.now().minus(1, ChronoUnit.HOURS));
        when(vcsRepository.findFirstByClientId(APP_ID)).thenReturn(null);
        doAnswer(invocation -> {
            throw new SchedulerException("boom");
        }).when(scheduleGitHubAppTokenService).deleteTask(orphaned.getId().toString());

        String result = subject.refreshAccessToken(orphaned);

        assertNull(result);
        verify(gitHubAppTokenRepository, times(1)).delete(orphaned);
    }

    // Repository discovery calls getInstallationToken directly, without ever caching or
    // checking expiry - a response with no expires_at (or a caller/mock that omits it) must
    // not blow up the whole call with a DateTimeParseException.
    @Test
    public void getInstallationToken_missingExpiresAt_doesNotThrow() throws Exception {
        Vcs vcs = createVcs();
        includeExpiresAt = false;
        String jws = subject.generateAppJwt(vcs);

        String result = subject.getInstallationToken(INSTALLATION_ID, vcs.getApiUrl(), jws, OWNER);

        assertEquals(tokenToReturn, result);
        assertEquals(1, accessTokenHits.get());
    }

    @Test
    public void refreshAccessToken_matchingVcs_returnsRefreshedTokenAndSetsExpiry() throws Exception {
        Vcs vcs = createVcs();
        GitHubAppToken existing = createCachedToken(Instant.now().minus(1, ChronoUnit.HOURS));
        when(vcsRepository.findFirstByClientId(APP_ID)).thenReturn(vcs);

        String result = subject.refreshAccessToken(existing);

        assertEquals(tokenToReturn, result);
        assertNotNull(existing.getExpiresAt());
        assertEquals(1, accessTokenHits.get());
    }
}
