package io.terrakube.api.plugin.vcs.provider.gitlab;

import io.terrakube.api.plugin.vcs.provider.exception.TokenException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Service
public class GitLabTokenService {

    private static final String DEFAULT_ENDPOINT="https://gitlab.com";

    @Value("${io.terrakube.hostname}")
    private String hostname;

    @Autowired
    private WebClient.Builder webClientBuilder;

    public GitLabToken getAccessToken(String vcsId, String clientId, String clientSecret, String tempCode, String callback, String endpoint) throws TokenException {
        GitLabToken gitLabToken = getWebClient(endpoint).post().uri(uriBuilder -> uriBuilder.path("/oauth/token")
                        .queryParam("client_id", clientId)
                        .queryParam("client_secret", clientSecret)
                        .queryParam("code",tempCode)
                        .queryParam("grant_type", "authorization_code")
                        .queryParam("redirect_uri", String.format("https://%s/callback/v1/vcs/%s", hostname, callback == null ? vcsId: callback))
                        .build())
                .retrieve().bodyToMono(GitLabToken.class).block();

        return validateNewToken(gitLabToken);
    }

    public GitLabToken refreshAccessToken(String vcsId, String clientId, String clientSecret, String refreshToken, String callback, String endpoint) throws TokenException {
        GitLabToken gitLabToken = getWebClient(endpoint).post().uri(uriBuilder -> uriBuilder.path("/oauth/token")
                        .queryParam("client_id", clientId)
                        .queryParam("client_secret", clientSecret)
                        .queryParam("refresh_token", refreshToken)
                        .queryParam("grant_type", "refresh_token")
                        .queryParam("redirect_uri", String.format("https://%s/callback/v1/vcs/%s", hostname, callback == null ? vcsId: callback))
                        .build())
                .retrieve().bodyToMono(GitLabToken.class).block();

        return validateNewToken(gitLabToken);
    }

    private WebClient getWebClient(String endpoint){
        return webClientBuilder
                .baseUrl((endpoint != null)? endpoint : DEFAULT_ENDPOINT)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(
                        new ReactorClientHttpConnector(
                                HttpClient.create().proxyWithSystemProperties())
                )
                .build();
    }

    private GitLabToken validateNewToken(GitLabToken gitLabToken) throws TokenException {
        if(gitLabToken != null) {
            return gitLabToken;
        } else {
            throw new TokenException("500","Unable to get Gitlab Token");
        }
    }
}
