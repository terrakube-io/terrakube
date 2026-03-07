package io.terrakube.api.plugin.scheduler;

import java.util.Optional;
import java.util.UUID;

import io.terrakube.api.rs.vcs.VcsType;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import io.terrakube.api.plugin.vcs.TokenService;
import io.terrakube.api.repository.GitHubAppTokenRepository;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.rs.vcs.Vcs;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Deprecated
@AllArgsConstructor
@Component
@Getter
@Setter
@Slf4j
public class ScheduleVcs implements org.quartz.Job {

    public static final String VCS_ID = "vcsId";

    TokenService tokenService;
    VcsRepository vcsRepository;
    GitHubAppTokenRepository gitHubAppTokenRepository;
    ScheduleGitHubAppTokenService scheduleGitHubAppTokenService;

    @Transactional
    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        String vcsId = jobExecutionContext.getJobDetail().getJobDataMap().getString(VCS_ID);
        Vcs vcs = null;
        Optional<Vcs> search = vcsRepository.findById(UUID.fromString(vcsId));
        if (search.isPresent()) {
            log.info("VCS connection found using Id");
            vcs = search.get();
        } else {
            vcs = vcsRepository.findByCallback(vcsId);
            if (vcs == null) {
                log.warn(
                        "VCS Job Id {} is still active but no longer needed it, vcs connection cannot be found in the database",
                        vcsId);
                return;
            }
            log.info("VCS found with custom callback");
        }

        // Special case Azure Managed Identity does not require refreshing the token, if anything exists here, we could be safely deleted is no longer need it
        if (vcs.getVcsType().equals(VcsType.AZURE_SP_MI)) {
            try {
                jobExecutionContext.getScheduler().deleteJob(new JobKey("TerrakubeV2_Vcs_" + vcs.getId()));
                return;
            } catch (SchedulerException e) {
                log.error(e.getMessage());
            }
        }

        log.warn("Quartz job no longer need it to refresh credentials {}", vcs.getId());
    }
}
