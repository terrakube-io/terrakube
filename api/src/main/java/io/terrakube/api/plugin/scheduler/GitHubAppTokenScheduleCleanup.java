package io.terrakube.api.plugin.scheduler;

import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Removes the GitHub App token refresh jobs left behind by earlier versions.
 *
 * Those versions refreshed every installation token on a 55 minute timer. Tokens are
 * now minted on demand when a caller finds the cached one expired, so the timer is
 * gone - but its triggers are persisted in the Quartz tables of any existing
 * deployment, and firing one now would fail to instantiate a class that no longer
 * exists. Unscheduling them on startup keeps an upgrade quiet.
 */
@Component
@Slf4j
public class GitHubAppTokenScheduleCleanup {

    private static final String LEGACY_JOB_PREFIX = "TerrakubeV2_GitHubAppToken_";

    @Autowired
    Scheduler scheduler;

    @EventListener(ApplicationReadyEvent.class)
    public void removeLegacyRefreshJobs() {
        try {
            int removed = 0;
            for (JobKey jobKey : scheduler.getJobKeys(GroupMatcher.anyJobGroup())) {
                if (jobKey.getName().startsWith(LEGACY_JOB_PREFIX) && scheduler.deleteJob(jobKey)) {
                    removed++;
                }
            }
            if (removed > 0) {
                log.info("Removed {} legacy GitHub App token refresh job(s); tokens are now minted on demand", removed);
            }
        } catch (SchedulerException e) {
            // Nothing depends on this cleanup succeeding: a leftover trigger only logs
            // noise, so a failure here must not stop the application from starting.
            log.warn("Could not remove the legacy GitHub App token refresh jobs", e);
        }
    }
}
