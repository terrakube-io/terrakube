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
import org.springframework.web.client.HttpStatusCodeException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.terrakube.api.repository.GitHubAppTokenRepository;
import io.terrakube.api.rs.vcs.GitHubAppToken;
import io.terrakube.api.rs.vcs.Vcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
    // An installation id GitHub no longer accepts, e.g. the app was reinstalled after
    // the row was written.
    private static final String STALE_INSTALLATION_ID = "11111";
    // An owner no installation serves: nothing is registered for it, so its lookup 404s.
    private static final String UNSERVED_OWNER = "ghostowner";

    private GitHubAppTokenRepository gitHubAppTokenRepository;
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

        subject = new GitHubTokenService();
        subject.objectMapper = new ObjectMapper();
        subject.gitHubAppTokenRepository = gitHubAppTokenRepository;

        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";

        accessTokenHits = new AtomicInteger(0);
        installationHits = new AtomicInteger(0);
        tokenToReturn = "ghs_minted-token";
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
        token.setToken("ghs_cached-token");
        token.setExpiresAt(expiresAt);
        return token;
    }

    // A token that is still valid must be served without touching GitHub: minting is
    // what replaced the timer, so it has to stay confined to the moment one is needed.
    @Test
    public void getGitHubAppToken_validCachedToken_isServedWithoutAnyHttpCall() throws Exception {
        Vcs vcs = createVcs();
        GitHubAppToken cached = createCachedToken(Instant.now().plus(30, ChronoUnit.MINUTES));

        when(gitHubAppTokenRepository.findByAppIdAndOwner(APP_ID, OWNER)).thenReturn(cached);

        GitHubAppToken result = subject.getGitHubAppToken(vcs, new String[] { OWNER, REPO });

        assertEquals("ghs_cached-token", result.getToken());
        assertEquals(0, accessTokenHits.get());
        assertEquals(0, installationHits.get());
        verify(gitHubAppTokenRepository, never()).save(any(GitHubAppToken.class));
    }

    @Test
    public void getGitHubAppToken_expiredCachedToken_mintsAndUpdatesInPlace() throws Exception {
        Vcs vcs = createVcs();
        GitHubAppToken cached = createCachedToken(Instant.now().minus(5, ChronoUnit.MINUTES));

        when(gitHubAppTokenRepository.findByAppIdAndOwner(APP_ID, OWNER)).thenReturn(cached);
        when(gitHubAppTokenRepository.save(any(GitHubAppToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GitHubAppToken result = subject.getGitHubAppToken(vcs, new String[] { OWNER, REPO });

        assertEquals(tokenToReturn, result.getToken());
        assertEquals(cached.getId(), result.getId(), "must update the existing row, not create a new one");
        assertEquals(1, accessTokenHits.get());
        verify(gitHubAppTokenRepository, times(1)).save(any(GitHubAppToken.class));
    }

    // Rows written before an expiry was recorded must be treated as expired, so they
    // self-heal on the next read instead of being served forever. This is the failure
    // that made a missed refresh indistinguishable from a healthy cache.
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
    public void getGitHubAppToken_noCachedRow_mintsAndCachesOne() throws Exception {
        Vcs vcs = createVcs();
        when(gitHubAppTokenRepository.findByAppIdAndOwner(APP_ID, OWNER)).thenReturn(null);
        when(gitHubAppTokenRepository.save(any(GitHubAppToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GitHubAppToken result = subject.getGitHubAppToken(vcs, new String[] { OWNER, REPO });

        assertEquals(tokenToReturn, result.getToken());
        assertEquals(OWNER, result.getOwner());
        assertEquals(APP_ID, result.getAppId());
        assertEquals(INSTALLATION_ID, result.getInstallationId());
        assertEquals(1, installationHits.get());
        assertEquals(1, accessTokenHits.get());
    }

    // The installation is resolved on every mint, so an id GitHub no longer accepts is
    // simply replaced. Trusting the cached id is what left an owner permanently broken
    // with nothing in the flow willing to reconsider it.
    @Test
    public void getGitHubAppToken_staleInstallationId_isReplacedOnMint() throws Exception {
        Vcs vcs = createVcs();
        GitHubAppToken cached = createCachedToken(Instant.now().minus(5, ChronoUnit.MINUTES));
        cached.setInstallationId(STALE_INSTALLATION_ID);

        when(gitHubAppTokenRepository.findByAppIdAndOwner(APP_ID, OWNER)).thenReturn(cached);
        when(gitHubAppTokenRepository.save(any(GitHubAppToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GitHubAppToken result = subject.getGitHubAppToken(vcs, new String[] { OWNER, REPO });

        assertEquals(tokenToReturn, result.getToken());
        assertEquals(INSTALLATION_ID, result.getInstallationId(), "the corrected installation id must be stored");
        assertEquals(1, installationHits.get(), "the stale id is never used to mint");
        assertEquals(1, accessTokenHits.get());
    }

    // A failure must surface rather than degrade into a null or expired token: that is
    // what reaches the user as an unexplained "not authorized" from the git client. The
    // cached row must also survive, so a later attempt can still use it.
    @Test
    public void getGitHubAppToken_noInstallationServesRepository_failsAndKeepsRow() throws Exception {
        Vcs vcs = createVcs();
        GitHubAppToken cached = createCachedToken(Instant.now().minus(5, ChronoUnit.MINUTES));
        cached.setOwner(UNSERVED_OWNER);

        when(gitHubAppTokenRepository.findByAppIdAndOwner(APP_ID, UNSERVED_OWNER)).thenReturn(cached);

        assertThrows(HttpStatusCodeException.class,
                () -> subject.getGitHubAppToken(vcs, new String[] { UNSERVED_OWNER, REPO }));

        assertEquals("ghs_cached-token", cached.getToken(), "the row must not be blanked by a failed mint");
        verify(gitHubAppTokenRepository, never()).save(any(GitHubAppToken.class));
    }

    // A first mint that cannot resolve an installation must not cache anything: a row
    // with no token poisons the cache, since every later read finds it and hands back a
    // null token.
    @Test
    public void getGitHubAppToken_firstMintWithNoInstallation_cachesNothing() throws Exception {
        Vcs vcs = createVcs();
        when(gitHubAppTokenRepository.findByAppIdAndOwner(APP_ID, UNSERVED_OWNER)).thenReturn(null);

        assertThrows(HttpStatusCodeException.class,
                () -> subject.getGitHubAppToken(vcs, new String[] { UNSERVED_OWNER, REPO }));

        verify(gitHubAppTokenRepository, never()).save(any(GitHubAppToken.class));
        assertEquals(0, accessTokenHits.get());
    }

    @Test
    public void getGitHubAppToken_repositoryMissing_isRejected() throws Exception {
        Vcs vcs = createVcs();
        when(gitHubAppTokenRepository.findByAppIdAndOwner(APP_ID, OWNER)).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> subject.getGitHubAppToken(vcs, new String[] { OWNER }));

        assertEquals(0, installationHits.get());
        verify(gitHubAppTokenRepository, never()).save(any(GitHubAppToken.class));
    }

    // Repository discovery mints with an installation id it already picked from
    // /app/installations, and never caches or checks expiry - a response with no
    // expires_at must not blow up that call.
    @Test
    public void getInstallationToken_missingExpiresAt_doesNotThrow() throws Exception {
        Vcs vcs = createVcs();
        includeExpiresAt = false;
        String jws = subject.generateAppJwt(vcs);

        String result = subject.getInstallationToken(INSTALLATION_ID, vcs.getApiUrl(), jws, OWNER);

        assertEquals(tokenToReturn, result);
        assertEquals(1, accessTokenHits.get());
    }
}
