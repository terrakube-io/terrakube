package io.terrakube.api.plugin.scheduler;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Optional;
import java.util.UUID;

import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import io.terrakube.api.plugin.vcs.provider.exception.VcsTokenAcquisitionException;
import io.terrakube.api.plugin.vcs.provider.github.GitHubTokenService;
import io.terrakube.api.repository.GitHubAppTokenRepository;
import io.terrakube.api.rs.vcs.GitHubAppToken;

import com.fasterxml.jackson.core.JsonProcessingException;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ScheduleGitHubAppToken implements Job {
    @Autowired
    GitHubAppTokenRepository gitHubAppTokenRepository;
    @Autowired
    ScheduleGitHubAppTokenService scheduleGitHubAppTokenService;
    @Autowired
    GitHubTokenService gitHubTokenService;

    @Override
    @Transactional
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String tokenId  = context.getJobDetail().getJobDataMap().getString(scheduleGitHubAppTokenService.getJobDataKey());
        String token = null;

        Optional<GitHubAppToken> search = gitHubAppTokenRepository.findById(UUID.fromString(tokenId));        
        if (search.isEmpty()) return;
        GitHubAppToken appToken = search.get();
        try {
            token = gitHubTokenService.refreshAccessToken(appToken);
            log.debug("Token refreshed for GitHub installation {} on organization/user {}", appToken.getId(), appToken.getOwner());
        } catch (JsonProcessingException | NoSuchAlgorithmException | InvalidKeySpecException
                | VcsTokenAcquisitionException e) {
            log.error("Failed to refresh token for GitHub installation {} on organization/user {}, error {}", appToken.getId(), appToken.getOwner(), e);
        }
        if (token != null) {
            appToken.setToken(token);
            gitHubAppTokenRepository.save(appToken);
        }
    }
    
}
