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
import io.terrakube.api.plugin.vcs.provider.github.GitHubTokenService;
import io.terrakube.api.repository.GitHubAppTokenRepository;
import io.terrakube.api.rs.vcs.GitHubAppToken;

import com.fasterxml.jackson.core.JsonProcessingException;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

@Deprecated
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
        log.warn("Refreshing task for GitHub App Token {} no longer need it", appToken.getId());
    }
    
}
