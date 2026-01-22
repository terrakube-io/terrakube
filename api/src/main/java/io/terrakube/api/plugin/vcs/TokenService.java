package io.terrakube.api.plugin.vcs;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.quartz.SchedulerException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.terrakube.api.plugin.scheduler.ScheduleVcsService;
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
    ScheduleVcsService scheduleVcsService;

    @Transactional
    public String generateAccessToken(String vcsId, String tempCode) {
        String result = "";
        Vcs vcs = vcsRepository.findByCallback(vcsId);
        if (vcs == null) {
            log.info("Searching VCS by Id");
            vcs = vcsRepository.getReferenceById(UUID.fromString(vcsId));
        } else {
            log.info("VCS found with custom callback");
        }
        int minutes = Calendar.getInstance().get(Calendar.MINUTE);
        try {
            switch (vcs.getVcsType()) {
                case GITHUB:
                    GitHubToken gitHubToken = gitHubTokenService.getAccessToken(vcs.getClientId(),
                            vcs.getClientSecret(), tempCode, null, vcs.getEndpoint());
                    vcs.setAccessToken(gitHubToken.getAccess_token());
                    break;
                case BITBUCKET:
                    BitBucketToken bitBucketToken = bitbucketTokenService.getAccessToken(vcs.getClientId(),
                            vcs.getClientSecret(), tempCode, null, vcs.getEndpoint());
                    vcs.setAccessToken(bitBucketToken.getAccess_token());
                    vcs.setRefreshToken(bitBucketToken.getRefresh_token());
                    vcs.setTokenExpiration(
                            new Date(System.currentTimeMillis() + bitBucketToken.getExpires_in() * 1000));
                    // Refresh token every hour, Bitbucket Token expire after 2 hours (7200 seconds)
                    scheduleVcsService.createTask(String.format(QUARTZ_EVERY_60_MINUTES, minutes), vcsId);
                    break;
                case GITLAB:
                    GitLabToken gitLabToken = gitLabTokenService.getAccessToken(vcs.getId().toString(),
                            vcs.getClientId(), vcs.getClientSecret(), tempCode, vcs.getCallback(), vcs.getEndpoint());
                    vcs.setAccessToken(gitLabToken.getAccess_token());
                    vcs.setRefreshToken(gitLabToken.getRefresh_token());
                    vcs.setTokenExpiration(new Date(System.currentTimeMillis() + gitLabToken.getExpires_in() * 1000));
                    // Refresh token every hour, GitLab Token expire after 2 hours (7200 seconds)
                    scheduleVcsService.createTask(String.format(QUARTZ_EVERY_60_MINUTES, minutes), vcsId);
                    break;
                case AZURE_DEVOPS:
                    AzDevOpsToken azDevOpsToken = azDevOpsTokenService.getAccessToken(vcs.getId().toString(),
                            vcs.getClientSecret(), tempCode, vcs.getCallback(), vcs.getEndpoint());
                    vcs.setAccessToken(azDevOpsToken.getAccess_token());
                    vcs.setRefreshToken(azDevOpsToken.getRefresh_token());
                    vcs.setTokenExpiration(new Date(System.currentTimeMillis() + azDevOpsToken.getExpires_in() * 1000));
                    // Refresh token every 30 minutes, Azure DevOps Token expire after 1 hour (3599
                    // seconds)
                    scheduleVcsService.createTask(String.format(QUARTZ_EVERY_30_MINUTES, minutes), vcsId);
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
        } catch (SchedulerException e) {
            log.error(e.getMessage());
        } catch (ParseException e) {
            log.error(e.getMessage());
        }

        return result;
    }

    public Map refreshAccessToken(String vcsId, VcsType vcsType, Date tokenExpiration, String clientId,
            String clientSecret, String refreshToken, String callback, String endpoint) {
        Map<String, Object> tokenInformation = new HashMap<>();
        log.info("Renew Token before: {} {}", tokenExpiration, vcsId);

        switch (vcsType) {
            case BITBUCKET:
                try {
                    BitBucketToken bitBucketToken = bitbucketTokenService.refreshAccessToken(clientId, clientSecret,
                            refreshToken, endpoint);
                    tokenInformation.put("accessToken", bitBucketToken.getAccess_token());
                    tokenInformation.put("refreshToken", bitBucketToken.getRefresh_token());
                    tokenInformation.put("tokenExpiration",
                            new Date(System.currentTimeMillis() + bitBucketToken.getExpires_in() * 1000));
                } catch (TokenException e) {
                    log.error(e.getMessage());
                }
                break;
            case GITLAB:
                try {
                    GitLabToken gitLabToken = gitLabTokenService.refreshAccessToken(vcsId, clientId, clientSecret,
                            refreshToken, callback, endpoint);
                    tokenInformation.put("accessToken", gitLabToken.getAccess_token());
                    tokenInformation.put("refreshToken", gitLabToken.getRefresh_token());
                    tokenInformation.put("tokenExpiration",
                            new Date(System.currentTimeMillis() + gitLabToken.getExpires_in() * 1000));
                } catch (TokenException e) {
                    log.error(e.getMessage());
                }
                break;
            case AZURE_DEVOPS:
                try {
                    AzDevOpsToken azDevOpsToken = azDevOpsTokenService.refreshAccessToken(vcsId, clientSecret,
                            refreshToken, callback, endpoint);
                    tokenInformation.put("accessToken", azDevOpsToken.getAccess_token());
                    tokenInformation.put("refreshToken", azDevOpsToken.getRefresh_token());
                    tokenInformation.put("tokenExpiration",
                            new Date(System.currentTimeMillis() + azDevOpsToken.getExpires_in() * 1000));
                } catch (TokenException e) {
                    log.error(e.getMessage());
                }
                break;
            default:
                break;
        }
        return tokenInformation;
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
        // If the token is already set, return it, normally this is oAuth token
        if (token!=null && !token.isEmpty()) return token;

        // this is a special case for Azure DevOps with Managed Identity
        if (vcs.getVcsType().equals(VcsType.AZURE_SP_MI)) return "";

        URI uri = new URI(gitPath);
        String[] ownerAndRepo = Arrays.copyOfRange(uri.getPath().replaceAll("\\.git$", "").split("/"), 1, 3);
        // Otherwise, get the token from other table, currently only Github is supported.
        return  gitHubTokenService.getAccessToken(vcs, ownerAndRepo);
    }
}
