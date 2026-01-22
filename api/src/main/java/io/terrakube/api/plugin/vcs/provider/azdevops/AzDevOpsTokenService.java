package io.terrakube.api.plugin.vcs.provider.azdevops;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.core.http.ProxyOptions;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import io.terrakube.api.plugin.token.dynamic.DynamicCredentialsService;
import io.terrakube.api.plugin.vcs.provider.exception.TokenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.net.InetSocketAddress;
import java.util.Collections;

@Service
@Slf4j
public class AzDevOpsTokenService {

    @Value("${io.terrakube.hostname}")
    private String hostname;

    @Autowired
    private DynamicCredentialsService dynamicCredentialsService;

    @Autowired
    private WebClient.Builder webClientBuilder;

    private static final String DEFAULT_ENDPOINT="https://app.vssps.visualstudio.com";

    public AzDevOpsToken getAccessToken(String vcsId, String clientSecret, String tempCode, String callback, String endpoint) throws TokenException {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        formData.add("client_assertion", clientSecret);
        formData.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        formData.add("assertion", tempCode);
        formData.add("redirect_uri", String.format("https://%s/callback/v1/vcs/%s", hostname, callback == null ? vcsId: callback));

        AzDevOpsToken azDevOpsToken = getWebClient(endpoint).post()
                .uri("/oauth2/token")
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .bodyToMono(AzDevOpsToken.class)
                .block();

        return validateNewToken(azDevOpsToken);
    }

    public AzDevOpsToken refreshAccessToken(String vcsId, String clientSecret, String refreshToken, String callback, String endpoint) throws TokenException {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        formData.add("client_assertion", clientSecret);
        formData.add("grant_type", "refresh_token");
        formData.add("assertion", refreshToken);
        formData.add("redirect_uri", String.format("https://%s/callback/v1/vcs/%s", hostname, callback == null ? vcsId: callback));

        AzDevOpsToken azDevOpsToken  = getWebClient(endpoint).post()
                .uri("/oauth2/token")
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .bodyToMono(AzDevOpsToken.class)
                .block();

        return validateNewToken(azDevOpsToken);
    }

    private WebClient getWebClient(String endpoint){
        return webClientBuilder
                .baseUrl((endpoint != null)? endpoint : DEFAULT_ENDPOINT)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .clientConnector(
                        new ReactorClientHttpConnector(
                                HttpClient.create().proxyWithSystemProperties())
                )
                .build();
    }

    private AzDevOpsToken validateNewToken(AzDevOpsToken azDevOpsToken) throws TokenException {
        if(azDevOpsToken != null) {
            return azDevOpsToken;
        } else {
            throw new TokenException("500","Unable to get Azure DevOps Token");
        }
    }
}
