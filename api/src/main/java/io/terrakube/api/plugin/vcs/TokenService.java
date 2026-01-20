package io.terrakube.api.plugin.vcs;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.text.ParseException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.quartz.SchedulerException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.terrakube.api.plugin.vcs.provider.azdevops.AzDevOpsToken;
import io.terrakube.api.plugin.vcs.provider.azdevops.AzDevOpsTokenService;
import io.terrakube.api.plugin.vcs.provider.bitbucket.BitBucketToken;
import io.terrakube.api.plugin.vcs.provider.bitbucket.BitbucketTokenService;
import io.terrakube.api.plugin.vcs.provider.exception.TokenException;
import io.terrakube.api.plugin.vcs.provider.github.GitHubToken;
import io.terrakube.api.plugin.vcs.provider.github.GitHubTokenService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabToken;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabTokenService;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsStatus;
import io.terrakube.api.rs.vcs.VcsType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
@Service
public class TokenService {

    public static final String QUARTZ_EVERY_60_MINUTES = "0 0 0/1 ? * *";
    public static final String QUARTZ_EVERY_30_MINUTES = "0 0/30 * ? * *";

    VcsRepository vcsRepository;
    GitHubTokenService gitHubTokenService;
    BitbucketTokenService bitbucketTokenService;
    GitLabTokenService gitLabTokenService;
    AzDevOpsTokenService azDevOpsTokenService;

    public String generateAccessToken(String vcsId, String tempCode) {
        String result = "";
        Vcs vcs = vcsRepository.findByCallback(vcsId);
        if (vcs == null) {
            log.info("Searching VCS by Id");
            vcs = vcsRepository.getReferenceById(UUID.fromString(vcsId));
        } else {
            log.info("VCS found with custom callback");
        }
        try {
            switch (vcs.getVcsType()) {
                case GITHUB:
                    GitHubToken gitHubToken = gitHubTokenService.getAccessToken(vcs.getClientId(),
                            vcs.getClientSecret(), tempCode, null, vcs.getEndpoint());
                    vcs.setAccessToken(gitHubToken.getAccess_token());
                    vcs.setRefreshToken(gitHubToken.getRefresh_token());
                    vcs.setTokenExpiration(
                            new Date(System.currentTimeMillis() + gitHubToken.getExpires_in() * 1000));
                    break;
                case BITBUCKET:
                    BitBucketToken bitBucketToken = bitbucketTokenService.getAccessToken(vcs.getClientId(),
                            vcs.getClientSecret(), tempCode, null, vcs.getEndpoint());
                    vcs.setAccessToken(bitBucketToken.getAccess_token());
                    vcs.setRefreshToken(bitBucketToken.getRefresh_token());
                    vcs.setTokenExpiration(
                            new Date(System.currentTimeMillis() + bitBucketToken.getExpires_in() * 1000));
                    break;
                case GITLAB:
                    GitLabToken gitLabToken = gitLabTokenService.getAccessToken(vcs.getId().toString(),
                            vcs.getClientId(), vcs.getClientSecret(), tempCode, vcs.getCallback(), vcs.getEndpoint());
                    vcs.setAccessToken(gitLabToken.getAccess_token());
                    vcs.setRefreshToken(gitLabToken.getRefresh_token());
                    vcs.setTokenExpiration(new Date(System.currentTimeMillis() + gitLabToken.getExpires_in() * 1000));
                    break;
                case AZURE_DEVOPS:
                    AzDevOpsToken azDevOpsToken = azDevOpsTokenService.getAccessToken(vcs.getId().toString(),
                            vcs.getClientSecret(), tempCode, vcs.getCallback(), vcs.getEndpoint());
                    vcs.setAccessToken(azDevOpsToken.getAccess_token());
                    vcs.setRefreshToken(azDevOpsToken.getRefresh_token());
                    vcs.setTokenExpiration(new Date(System.currentTimeMillis() + azDevOpsToken.getExpires_in() * 1000));
                    break;
                case AZURE_SP_MI:
                    AzDevOpsToken azDevOpsTokenDynamic = azDevOpsTokenService.getAzureDefaultToken();
                    vcs.setAccessToken(azDevOpsTokenDynamic.getAccess_token());
                    vcs.setRefreshToken(azDevOpsTokenDynamic.getRefresh_token());
                    vcs.setTokenExpiration(new Date(System.currentTimeMillis() + azDevOpsTokenDynamic.getExpires_in() * 1000));
                    break;
                default:
                    break;
            }
            vcs.setStatus(VcsStatus.COMPLETED);
            vcsRepository.save(vcs);
            result = vcs.getRedirectUrl();
        } catch (TokenException e) {
            log.error(e.getMessage());
            vcs.setStatus(VcsStatus.ERROR);
            vcsRepository.save(vcs);
        }

        return result;
    }

    private void refreshAccessToken(Vcs vcs) throws TokenException {
        String vcsId = vcs.getId().toString();
        String clientId = vcs.getClientId();
        String clientSecret = vcs.getClientSecret();
        String refreshToken = vcs.getRefreshToken();
        String endpoint = vcs.getEndpoint();
        String callback = vcs.getCallback();

        switch (vcs.getVcsType()) {
            case BITBUCKET:
                    BitBucketToken bitBucketToken = bitbucketTokenService.refreshAccessToken(clientId, clientSecret,
                            refreshToken, endpoint);
                    vcs.setAccessToken(bitBucketToken.getAccess_token());
                    vcs.setRefreshToken(bitBucketToken.getRefresh_token());
                    vcs.setTokenExpiration(new Date(System.currentTimeMillis() + bitBucketToken.getExpires_in() * 1000));
                break;
            case GITLAB:
                    GitLabToken gitLabToken = gitLabTokenService.refreshAccessToken(vcsId, clientId, clientSecret,
                            refreshToken, callback, endpoint);
                    vcs.setAccessToken(gitLabToken.getAccess_token());
                    vcs.setRefreshToken(gitLabToken.getRefresh_token());
                    vcs.setTokenExpiration(new Date(System.currentTimeMillis() + gitLabToken.getExpires_in() * 1000));
                break;
            case AZURE_DEVOPS:
                    AzDevOpsToken azDevOpsToken = azDevOpsTokenService.refreshAccessToken(vcsId, clientSecret,
                            refreshToken, callback, endpoint);
                    vcs.setAccessToken(azDevOpsToken.getAccess_token());
                    vcs.setRefreshToken(azDevOpsToken.getRefresh_token());
                    vcs.setTokenExpiration(new Date(System.currentTimeMillis() + azDevOpsToken.getExpires_in() * 1000));
                break;
            case AZURE_SP_MI:
                AzDevOpsToken azDevOpsTokenDynamic = null;
                    azDevOpsTokenDynamic = azDevOpsTokenService.getAzureDefaultToken();
                    vcs.setAccessToken(azDevOpsTokenDynamic.getAccess_token());
                    vcs.setRefreshToken(azDevOpsTokenDynamic.getRefresh_token());
                    vcs.setTokenExpiration(new Date(System.currentTimeMillis() + azDevOpsTokenDynamic.getExpires_in() * 1000));
                break;
            default:
                break;
        }
    }
    
    // Get the access token for access to the supplied repository, ownerAndRepo is
    // an array of the owner and the repository name
    public String getAccessToken(String[] ownerAndRepo, Vcs vcs)
            throws JsonMappingException, JsonProcessingException, NoSuchAlgorithmException, InvalidKeySpecException {
        String token = vcs.getAccessToken();
        // If the token is already set, return it, normally this is oAuth token
        if (token!=null && !token.isEmpty()) return token;
        
        // Otherwise, get the token from other table, currently only Github is supported.
        return  gitHubTokenService.getAccessToken(vcs, ownerAndRepo);
    }

    // Get the access token for access to the supplied repository in full URL
    public String getAccessToken(String gitPath, Vcs vcs) throws URISyntaxException, JsonMappingException,
            JsonProcessingException, NoSuchAlgorithmException, InvalidKeySpecException {
        String token = vcs.getAccessToken();
        Date expiry = vcs.getTokenExpiration();
        // If the token is already set, return it, normally this is oAuth token
        if (token!=null && !token.isEmpty() && expiry!=null && expiry.before(Date.from(Instant.now()))) return token;
        try {
            refreshAccessToken(vcs);
        } catch (TokenException e) {
            // TODO This error should be propagated
            e.printStackTrace();
        }
        return vcs.getAccessToken();
    }
}
