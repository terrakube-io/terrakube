package io.terrakube.api.plugin.vcs.provider.github;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import io.terrakube.api.plugin.scheduler.ScheduleGitHubAppTokenService;
import io.terrakube.api.plugin.vcs.provider.GetAccessToken;
import io.terrakube.api.plugin.vcs.provider.exception.TokenException;
import io.terrakube.api.repository.GitHubAppTokenRepository;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.rs.vcs.GitHubAppToken;
import io.terrakube.api.rs.vcs.Vcs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

@Slf4j
@Service
public class GitHubTokenService implements GetAccessToken<GitHubToken> {

    private static final String DEFAULT_ENDPOINT = "https://github.com";
    // Treat a cached token as expired slightly before GitHub actually expires it,
    // so a request never races the real expiry.
    private static final Duration EXPIRY_SAFETY_BUFFER = Duration.ofMinutes(1);

    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    GitHubAppTokenRepository gitHubAppTokenRepository;
    @Autowired
    VcsRepository vcsRepository;
    @Autowired
    ScheduleGitHubAppTokenService scheduleGitHubAppTokenService;

    public GitHubToken getAccessToken(String clientId, String clientSecret, String tempCode, String callback,
                                      String endpoint) throws TokenException {
        HttpClient httpClient;
        WebClient client;
        if(System.getProperty("http.proxyHost") != null) {
            log.info("Using proxy host: {} port: {}", System.getProperty("http.proxyHost"), System.getProperty("http.proxyPort"));

            httpClient = HttpClient.create()
                    .proxy(proxy -> proxy.type(ProxyProvider.Proxy.HTTP)
                            .host(System.getProperty("http.proxyHost"))
                            .port(Integer.parseInt(System.getProperty("http.proxyPort"))));

            client = WebClient.builder()
                    .baseUrl((endpoint != null)? endpoint : DEFAULT_ENDPOINT)
                    .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .build();
        } else {
            log.info("No proxy host specified, using default proxy");
            client = WebClient.builder()
                    .baseUrl((endpoint != null)? endpoint : DEFAULT_ENDPOINT)
                    .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .build();
        }


        log.info("Calling GitHub API");

        GitHubToken gitHubToken = client.post().uri(uriBuilder -> uriBuilder.path("/login/oauth/access_token")
                        .queryParam("client_id", clientId)
                        .queryParam("client_secret", clientSecret)
                        .queryParam("code", tempCode)
                        .build())
                .retrieve().bodyToMono(GitHubToken.class).block();

        if (gitHubToken != null)
            return gitHubToken;
        else {
            throw new TokenException("500", "Unable to get GitHub Token");
        }
    }

    public String getAccessToken(Vcs vcs, String[] ownerAndRepo)
            throws JsonMappingException, JsonProcessingException, NoSuchAlgorithmException, InvalidKeySpecException {
        return getGitHubAppToken(vcs, ownerAndRepo).getToken();
    }

    // Refreshes the access token for a specific installation of the app that's
    // already been saved in the GitHubAppToken table
    public String refreshAccessToken(GitHubAppToken gitHubAppToken)
            throws NoSuchAlgorithmException, InvalidKeySpecException, JsonMappingException, JsonProcessingException {
        Vcs vcs = vcsRepository.findFirstByClientId(gitHubAppToken.getAppId());
        if (vcs == null) {
            log.warn("No Vcs found for GitHub App id {}, removing orphaned GitHubAppToken {} for owner {}",
                    gitHubAppToken.getAppId(), gitHubAppToken.getId(), gitHubAppToken.getOwner());
            try {
                scheduleGitHubAppTokenService.deleteTask(gitHubAppToken.getId().toString());
            } catch (SchedulerException e) {
                log.error("Failed to delete refresh schedule for orphaned GitHubAppToken {}, error {}",
                        gitHubAppToken.getId(), e);
            }
            gitHubAppTokenRepository.delete(gitHubAppToken);
            return null;
        }
        String jws = generateJWT(vcs.getClientId(), vcs.getPrivateKey());
        // The scheduled refresh has no repository context, so the installation is
        // re-resolved from the owner alone when the cached id no longer works.
        GitHubAppInstallationToken installationToken = mintInstallationToken(vcs, jws, gitHubAppToken, null);
        gitHubAppToken.setExpiresAt(installationToken.expiresAt());
        return installationToken.token();
    }

    public GitHubAppToken getGitHubAppToken(Vcs vcs, String[] ownerAndRepo)
            throws JsonMappingException, JsonProcessingException, NoSuchAlgorithmException, InvalidKeySpecException {
        log.info("Getting access token for user/organization {} and vcs {}", ownerAndRepo[0], vcs.getId());
        GitHubAppToken gitHubAppToken = gitHubAppTokenRepository.findByAppIdAndOwner(vcs.getClientId(), ownerAndRepo[0]);
        if (gitHubAppToken == null) {
            log.info("No token found in GitHubAppToken table, fetching new token");
            gitHubAppToken = fetchGitHubAppInstallationToken(vcs, ownerAndRepo);
        } else if (isExpired(gitHubAppToken.getExpiresAt())) {
            log.info("Cached GitHub App token for user/organization {} is expired or missing an expiry, refreshing",
                    ownerAndRepo[0]);
            String jws = generateJWT(vcs.getClientId(), vcs.getPrivateKey());
            // A failure propagates rather than serving the expired token that is still
            // in the row: an expired token reaches the user as an unexplained
            // "not authorized", while the row itself stays intact for the next attempt.
            GitHubAppInstallationToken installationToken = mintInstallationToken(vcs, jws, gitHubAppToken,
                    repositoryOf(ownerAndRepo));
            gitHubAppToken.setToken(installationToken.token());
            gitHubAppToken.setExpiresAt(installationToken.expiresAt());
            gitHubAppToken = gitHubAppTokenRepository.save(gitHubAppToken);
        }

        log.info("Token fetched for user/organization {}", ownerAndRepo[0]);
        log.debug("Token: {}", gitHubAppToken.getToken());

        return gitHubAppToken;
    }

    // Generates a new access token for a specific installation of the app that
    // hasn't been saved in the GitHubAppToken table yet
    private GitHubAppToken fetchGitHubAppInstallationToken(Vcs vcs, String[] ownerAndRepo)
            throws JsonMappingException, JsonProcessingException, NoSuchAlgorithmException, InvalidKeySpecException {
        GitHubAppToken gitHubAppToken = new GitHubAppToken();

        String jws = generateJWT(vcs.getClientId(), vcs.getPrivateKey());
        log.info("Generated JWT token for GitHub App");
        String installationId = resolveInstallationId(vcs.getApiUrl(), jws, ownerAndRepo[0],
                repositoryOf(ownerAndRepo));
        if (installationId == null) {
            log.error("No installation of GitHub App {} serves user/organization {}, no token was created",
                    vcs.getClientId(), ownerAndRepo[0]);
            return gitHubAppToken;
        }
        log.info("Successfully fetched access token for user/organization {} and vcs {}", ownerAndRepo[0], vcs.getId());
        gitHubAppToken.setInstallationId(installationId);
        gitHubAppToken.setOwner(ownerAndRepo[0]);
        gitHubAppToken.setAppId(vcs.getClientId());
        GitHubAppInstallationToken installationToken = fetchGitHubAppInstallationToken(installationId,
                vcs.getApiUrl(), jws, ownerAndRepo[0]);
        if (installationToken.token() == null) {
            // Persisting a row with no token would poison the cache: every later read
            // would hit it, find no token, and hand the caller an unauthenticated clone.
            log.error("Installation {} of GitHub App {} returned no token for user/organization {}, nothing was saved",
                    installationId, vcs.getClientId(), ownerAndRepo[0]);
            return gitHubAppToken;
        }
        gitHubAppToken.setToken(installationToken.token());
        gitHubAppToken.setExpiresAt(installationToken.expiresAt());

        gitHubAppToken = gitHubAppTokenRepository.save(gitHubAppToken);
        log.info("Successfully saved token for user/organization {} and vcs {}", ownerAndRepo[0], vcs.getId());
        // Schedule a job to refresh the token every 55 minutes
        try {
            log.info("Scheduling task to refresh GitHub App token for owner/organization {}", gitHubAppToken.getOwner());
            scheduleGitHubAppTokenService.createTask(3300, gitHubAppToken.getId().toString());
            log.info("Successfully created schedule task to refresh GitHub App token for owner/organization {}",
                    gitHubAppToken.getOwner());
        } catch (SchedulerException e) {
            log.error("Failed to create schedule task to refresh GitHub App token for owner/organization {}, error {}",
                    gitHubAppToken.getOwner(), e);
        }
        return gitHubAppToken;
    }

    private static String repositoryOf(String[] ownerAndRepo) {
        return ownerAndRepo.length > 1 ? ownerAndRepo[1] : null;
    }

    // Works out which installation of the app serves this owner (and repository, when
    // known). One app can be installed on many accounts, and an installation id is not
    // stable - reinstalling, or installing on a second account, issues new ids - so a
    // cached id must never be trusted blindly.
    //
    // /repos/{owner}/{repo}/installation is tried first because it is the only endpoint
    // that answers "which installation serves THIS repository". The owner-level
    // endpoints are the fallback for callers with no repository in hand (the scheduled
    // refresh), and /users is tried after /orgs because an owner is one or the other.
    private String resolveInstallationId(String vcsApiUrl, String jws, String owner, String repo) {
        HttpStatusCodeException lastFailure = null;
        List<String> candidates = new ArrayList<>();
        if (repo != null && !repo.isBlank()) {
            candidates.add(vcsApiUrl + "/repos/" + owner + "/" + repo + "/installation");
        }
        candidates.add(vcsApiUrl + "/orgs/" + owner + "/installation");
        candidates.add(vcsApiUrl + "/users/" + owner + "/installation");

        for (String url : candidates) {
            try {
                ResponseEntity<String> response = callGithubAPI("", url, HttpMethod.GET, jws);
                if (response.getStatusCode().value() == 200) {
                    String installationId = objectMapper.readTree(response.getBody()).path("id").asText();
                    if (!installationId.isBlank()) {
                        log.info("Resolved installation {} for user/organization {} using {}", installationId, owner,
                                url);
                        return installationId;
                    }
                }
                log.debug("Installation lookup {} returned {} with no usable id", url, response.getStatusCode());
            } catch (HttpStatusCodeException e) {
                // A 404 only means the app is not installed for that owner or repository,
                // which is expected while walking the candidates.
                log.debug("Installation lookup {} returned {}", url, e.getStatusCode());
                lastFailure = e;
            } catch (JsonProcessingException e) {
                log.error("Could not parse the installation lookup response from {}", url, e);
            }
        }
        // Every candidate failed. Rethrowing keeps this as loud as it is today rather
        // than degrading into a null token, which reaches the user as an unexplained
        // "not authorized" from the git client.
        if (lastFailure != null) {
            throw lastFailure;
        }
        return null;
    }

    // Mints an installation token for a cached row, re-resolving the installation when
    // the cached id has gone stale. Returns null when no token could be obtained, so
    // callers can leave the cached row untouched rather than blanking it.
    private GitHubAppInstallationToken mintInstallationToken(Vcs vcs, String jws, GitHubAppToken gitHubAppToken,
            String repo) throws JsonProcessingException {
        String owner = gitHubAppToken.getOwner();
        String cachedInstallationId = gitHubAppToken.getInstallationId();

        if (cachedInstallationId != null && !cachedInstallationId.isBlank()) {
            try {
                GitHubAppInstallationToken minted = fetchGitHubAppInstallationToken(cachedInstallationId,
                        vcs.getApiUrl(), jws, owner);
                if (minted.token() != null) {
                    return minted;
                }
                log.warn("Installation {} returned no token for user/organization {}, re-resolving the installation",
                        cachedInstallationId, owner);
            } catch (HttpStatusCodeException e) {
                log.warn("Cached installation {} for user/organization {} is no longer usable ({}), re-resolving",
                        cachedInstallationId, owner, e.getStatusCode());
            }
        }

        String resolvedInstallationId = resolveInstallationId(vcs.getApiUrl(), jws, owner, repo);
        if (resolvedInstallationId == null) {
            return null;
        }
        if (!resolvedInstallationId.equals(cachedInstallationId)) {
            log.info("Installation for user/organization {} changed from {} to {}", owner, cachedInstallationId,
                    resolvedInstallationId);
            gitHubAppToken.setInstallationId(resolvedInstallationId);
        }
        // Anything thrown here reaches the caller on purpose: a retry that also fails
        // must not be reported as "no token".
        return fetchGitHubAppInstallationToken(resolvedInstallationId, vcs.getApiUrl(), jws, owner);
    }

    // Gets the access token with app installation ID for a specific installation of
    // the app
    private GitHubAppInstallationToken fetchGitHubAppInstallationToken(String installationId, String vcsApiUrl,
            String jws, String owner) throws JsonProcessingException {
        String token = null;
        Instant expiresAt = null;
        String url = vcsApiUrl + "/app/installations/" + installationId + "/access_tokens";
        log.debug("Getting access token for installation {} on user/organization {}", installationId, owner);
        ResponseEntity<String> tokenResponse = callGithubAPI("", url, HttpMethod.POST, jws);
        if (tokenResponse.getStatusCode().value() == 201) {
            JsonNode rootNode = objectMapper.readTree(tokenResponse.getBody());
            token = rootNode.path("token").asText();
            String expiresAtText = rootNode.path("expires_at").asText();
            expiresAt = expiresAtText.isEmpty() ? null : Instant.parse(expiresAtText);
        }
        return new GitHubAppInstallationToken(token, expiresAt);
    }

    private boolean isExpired(Instant expiresAt) {
        return expiresAt == null || !expiresAt.isAfter(Instant.now().plus(EXPIRY_SAFETY_BUFFER));
    }

    private record GitHubAppInstallationToken(String token, Instant expiresAt) {
    }

    // Generates a JWT token for the GitHub App
    private String generateJWT(String clientId, String privateKey)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        String keyPem = privateKey.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll(System.lineSeparator(), "");

        log.debug("Stripped PKCS8 private key starting with {} and ending with {}", keyPem.substring(0, 10),
                keyPem.substring(keyPem.length() - 10));
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyPem));
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PrivateKey key = keyFactory.generatePrivate(keySpec);

        // GitHub rejects a JWT if, from its own clock, "iat" looks like it's in the
        // future or "exp" is more than 10 minutes out. Backdating "iat" by 60 seconds
        // and keeping "exp" a bit under the 10-minute cap gives room for clock drift
        // between this server and GitHub's, as GitHub's own docs recommend.
        Instant now = Instant.now();
        String jws = Jwts.builder()
                .setIssuer(clientId)
                .setIssuedAt(Date.from(now.minus(60, ChronoUnit.SECONDS)))
                .setExpiration(Date.from(now.plus(9, ChronoUnit.MINUTES)))
                .signWith(key, SignatureAlgorithm.RS256)
                .compact();
        return jws;
    }

    // Calls the GitHub API
    private ResponseEntity<String> callGithubAPI(String body, String apiUrl, HttpMethod method, String jws) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/vnd.github+json");
        headers.set("Authorization", "Bearer " + jws);
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        RestTemplate restTemplate = getRestTemplateWithProxy();
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        return restTemplate.exchange(apiUrl, method, entity, String.class);
    }

    // Generates the app-level JWT used to call GitHub App management endpoints (e.g. listing installations)
    public String generateAppJwt(Vcs vcs) throws NoSuchAlgorithmException, InvalidKeySpecException {
        return generateJWT(vcs.getClientId(), vcs.getPrivateKey());
    }

    // Exposes the installation access token fetch for callers that already know the installation id
    // (e.g. repository discovery, where the installation is picked from /app/installations)
    public String getInstallationToken(String installationId, String apiUrl, String jws, String owner)
            throws JsonProcessingException {
        return fetchGitHubAppInstallationToken(installationId, apiUrl, jws, owner).token();
    }

    // Calls a GitHub API endpoint authenticated with the app JWT (used for /app/installations)
    public ResponseEntity<String> callGithubAppApi(String apiUrl, HttpMethod method, String jws) {
        return callGithubAPI("", apiUrl, method, jws);
    }

    public RestTemplate getRestTemplateWithProxy() {
        // JdkClientHttpRequestFactory (java.net.http.HttpClient) is used instead of the
        // legacy SimpleClientHttpRequestFactory (java.net.HttpURLConnection): the latter
        // was observed to intermittently return an empty error body even when the server
        // sent one (e.g. GitHub's actual "'Expiration time' claim..." message came back
        // as "[no body]"), which made failures from this client impossible to diagnose.
        if (System.getProperty("http.proxyHost") != null) {
            log.info("RestTemplate proxy host: {} port: {}", System.getProperty("http.proxyHost"), System.getProperty("http.proxyPort"));
            String proxyHost = System.getProperty("http.proxyHost");
            int proxyPort = Integer.parseInt(System.getProperty("http.proxyPort"));
            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                    .proxy(java.net.ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)))
                    .build();
            return new RestTemplate(new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient));
        } else {
            log.info("No proxy setup");
            return new RestTemplate(new org.springframework.http.client.JdkClientHttpRequestFactory());
        }
    }
}