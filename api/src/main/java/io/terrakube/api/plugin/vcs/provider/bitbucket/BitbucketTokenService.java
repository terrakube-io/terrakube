package io.terrakube.api.plugin.vcs.provider.bitbucket;

import lombok.extern.slf4j.Slf4j;
import io.terrakube.api.plugin.vcs.provider.GetAccessToken;
import io.terrakube.api.plugin.vcs.provider.exception.TokenException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Service
@Slf4j
public class BitbucketTokenService implements GetAccessToken<BitBucketToken> {
    @Autowired
    private WebClient.Builder webClientBuilder;

    private static final String DEFAULT_ENDPOINT="https://bitbucket.org";


    @Override
    public BitBucketToken getAccessToken(String clientId, String clientSecret, String tempCode, String callback, String endpoint) throws TokenException {
        BitBucketToken bitBucketToken = null;
        for (int newToken = 0; newToken < 5; newToken++) {
            try {
                WebClient client = getClient(clientId, clientSecret, endpoint);

                MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
                formData.add("grant_type", "authorization_code");
                formData.add("code", tempCode);

                log.info("Checking token {} attempt", newToken);

                bitBucketToken = client.post()
                        .uri("/site/oauth2/access_token")
                        .body(BodyInserters.fromFormData(formData))
                        .retrieve()
                        .bodyToMono(BitBucketToken.class)
                        .timeout(Duration.ofSeconds(10))
                        .block();
                break;

            } catch (Exception ex) {
                log.error("Error refreshing bitbucket token: {}", ex.getMessage());
                bitBucketToken = null;
            }
        }
        if (bitBucketToken != null) {
            return bitBucketToken;
        } else {
            throw new TokenException("500", "Unable to get Bitbucket Token");
        }
    }

    public BitBucketToken refreshAccessToken(String clientId, String clientSecret, String refreshToken, String endpoint) throws TokenException {
        BitBucketToken bitBucketToken = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                log.info("Getting Bitbucket refresh token {} attempt", attempt);
                WebClient client = getClient(clientId, clientSecret, endpoint);

                MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
                formData.add("grant_type", "refresh_token");
                formData.add("refresh_token", refreshToken);

                bitBucketToken = client.post()
                        .uri("/site/oauth2/access_token")
                        .body(BodyInserters.fromFormData(formData))
                        .retrieve()
                        .bodyToMono(BitBucketToken.class)
                        .timeout(Duration.ofSeconds(10))
                        .block();
                log.info("Successfully get bitbucket refresh token");
                break;
            } catch (Exception ex) {
                log.error("Error refreshing bitbucket token: {}", ex.getMessage());
                bitBucketToken = null;
            }
        }

        if (bitBucketToken == null)
            throw new TokenException("500", "Unable to get Bitbucket Token");

        return bitBucketToken;

    }

    private WebClient getClient(String clientId, String clientSecret, String endpoint) {
        return webClientBuilder
                .baseUrl((endpoint != null) ? endpoint : DEFAULT_ENDPOINT)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .defaultHeaders(header -> header.setBasicAuth(clientId, clientSecret))
                .build();
    }
}
