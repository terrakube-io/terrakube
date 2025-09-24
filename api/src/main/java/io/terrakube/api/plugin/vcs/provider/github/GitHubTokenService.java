package io.terrakube.api.plugin.vcs.provider.github;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
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
    @Autowired
    private WebClient.Builder webClientBuilder; 
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

            client = webClientBuilder
                    .baseUrl((endpoint != null)? endpoint : DEFAULT_ENDPOINT)
                    .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .build();
        } else {
            log.info("No proxy host specified, using default proxy");
            client = webClientBuilder
                    .baseUrl((endpoint != null)? endpoint : DEFAULT_ENDPOINT)
                    .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .build();
        }


        log.info("Calling Github API");

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
        String jws = generateJWT(vcs.getClientId(), vcs.getPrivateKey());
        return fetchGitHubAppInstallationToken(gitHubAppToken.getInstallationId(), vcs.getApiUrl(), jws,
                gitHubAppToken.getOwner());
    }

    public GitHubAppToken getGitHubAppToken(Vcs vcs, String[] ownerAndRepo)
            throws JsonMappingException, JsonProcessingException, NoSuchAlgorithmException, InvalidKeySpecException {
        GitHubAppToken gitHubAppToken = gitHubAppTokenRepository.findByAppIdAndOwner(vcs.getClientId(), ownerAndRepo[0]);
        if (gitHubAppToken == null) {
            gitHubAppToken = fetchGitHubAppInstallationToken(vcs, ownerAndRepo);
        }

        return gitHubAppToken;
    }

    // Generates a new access token for a specific installation of the app that
    // hasn't been saved in the GitHubAppToken table yet
    private GitHubAppToken fetchGitHubAppInstallationToken(Vcs vcs, String[] ownerAndRepo)
            throws JsonMappingException, JsonProcessingException, NoSuchAlgorithmException, InvalidKeySpecException {
        GitHubAppToken gitHubAppToken = new GitHubAppToken();

        String jws = generateJWT(vcs.getClientId(), vcs.getPrivateKey());

        String url = vcs.getApiUrl() + "/repos/" + String.join("/", ownerAndRepo) + "/installation";
        log.debug("Getting access token for user/organization {}", ownerAndRepo[0]);
        ResponseEntity<String> tokenResponse = callGithubAPI("", url, HttpMethod.GET, jws);
        if (tokenResponse.getStatusCode().value() == 200) {
            JsonNode rootNode = objectMapper.readTree(tokenResponse.getBody());
            String installationId = rootNode.path("id").asText();
            //gitHubAppToken.setId(UUID.randomUUID());
            gitHubAppToken.setInstallationId(installationId);
            gitHubAppToken.setOwner(ownerAndRepo[0]);
            gitHubAppToken.setAppId(vcs.getClientId());
            gitHubAppToken
                    .setToken(fetchGitHubAppInstallationToken(installationId, vcs.getApiUrl(), jws, ownerAndRepo[0]));
        }

        gitHubAppToken = gitHubAppTokenRepository.save(gitHubAppToken);
        // Schedule a job to refresh the token every 55 minutes
        try {
            scheduleGitHubAppTokenService.createTask(3300, gitHubAppToken.getId().toString());
            log.debug("Successfully created schedule task to refresh GitHub App token for owner/organization {}",
                    gitHubAppToken.getOwner());
        } catch (SchedulerException e) {
            log.error("Failed to create schedule task to refresh GitHub App token for owner/organization {}, error {}",
                    gitHubAppToken.getOwner(), e);
        }
        return gitHubAppToken;
    }

    // Gets the access token with app installation ID for a specific installation of
    // the app
    private String fetchGitHubAppInstallationToken(String installationId, String vcsApiUrl, String jws, String owner)
            throws JsonMappingException, JsonProcessingException {
        String token = null;
        String url = vcsApiUrl + "/app/installations/" + installationId + "/access_tokens";
        log.debug("Getting access token for installation {} on user/organization {}", installationId, owner);
        ResponseEntity<String> tokenResponse = callGithubAPI("", url, HttpMethod.POST, jws);
        if (tokenResponse.getStatusCode().value() == 201) {
            token = objectMapper.readTree(tokenResponse.getBody()).path("token").asText();
        }
        return token;
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

        Instant now = Instant.now();
        String jws = Jwts.builder()
                .setIssuer(clientId)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plus(10, ChronoUnit.MINUTES)))
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

    public RestTemplate getRestTemplateWithProxy() {
        if (System.getProperty("http.proxyHost") != null) {
            log.info("RestTemplate proxy host: {} port: {}", System.getProperty("http.proxyHost"), System.getProperty("http.proxyPort"));
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            String proxyHost = System.getProperty("http.proxyHost");
            int proxyPort = Integer.parseInt(System.getProperty("http.proxyPort"));
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            requestFactory.setProxy(proxy);
            return new RestTemplate(requestFactory);
        } else {
            log.info("No proxy setup");
            return new RestTemplate();
        }
    }
}