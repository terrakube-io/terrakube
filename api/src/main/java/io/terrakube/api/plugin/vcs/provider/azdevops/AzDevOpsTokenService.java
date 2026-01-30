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
    private static final String AZURE_DEVOPS_SCOPE = "499b84ac-1321-427f-aa17-267ca6975798/.default"; // Azure DevOps scope

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

    public String getAzureDefaultToken() {
        try {
            log.debug("Getting Azure Default Token");
            DefaultAzureCredentialBuilder credBuilder = new DefaultAzureCredentialBuilder();

            String proxyHostname = System.getProperty("http.proxyHost");
            String proxyPortNumber = System.getProperty("http.proxyPort");
            if (proxyHostname != null && !proxyHostname.isEmpty() && proxyPortNumber != null && !proxyPortNumber.isEmpty()) {
                ProxyOptions proxyOptions = new ProxyOptions(
                        ProxyOptions.Type.HTTP,
                        new InetSocketAddress(
                                proxyHostname,
                                Integer.parseInt(proxyPortNumber)
                        )
                );
                log.debug("Using proxy host: {} port: {}", proxyHostname, proxyPortNumber);

                String proxyUsername = System.getProperty("http.proxyUser");
                String proxyPass = System.getProperty("http.proxyPassword");
                if (proxyUsername != null && !proxyUsername.isEmpty() && proxyPass != null && !proxyPass.isEmpty()) {

                    proxyOptions.setCredentials(
                            proxyUsername,
                            proxyPass
                    );
                }
                log.debug("Using proxy credentials: {}:{}", proxyUsername, proxyPass);
                credBuilder.httpClient(
                        new NettyAsyncHttpClientBuilder().proxy(proxyOptions).build()
                );
            }

            log.debug("Using Scope {}", AZURE_DEVOPS_SCOPE);
            DefaultAzureCredential credential = credBuilder.build();
            TokenRequestContext context = new TokenRequestContext()
                    .setScopes(Collections.singletonList(AZURE_DEVOPS_SCOPE));
            AccessToken accessToken = credential.getToken(context).block();
            if (accessToken == null || accessToken.getToken() == null) {
                throw new Exception("Failed to acquire Managed Identity token. Check your environment configuration in Azure.");
            }
            log.debug("Azure Default Token: {}", accessToken.getToken());
            return accessToken.getToken();
        } catch (Exception ex) {
            log.error("Error getting Azure Default Managed Identity Token: {}", ex.getMessage());
            return "";
        }
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
