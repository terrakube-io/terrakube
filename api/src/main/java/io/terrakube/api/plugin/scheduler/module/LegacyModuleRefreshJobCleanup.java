package io.terrakube.api.plugin.scheduler.module;

import java.util.Set;

import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Deletes the per-module refresh jobs that releases before the poller stored. Matches on the job class, not
 * only the name: module storage cleanup jobs used to be stored under the same name prefix.
 */
@Slf4j
@Component
@AllArgsConstructor
public class LegacyModuleRefreshJobCleanup {

    static final String LEGACY_JOB_PREFIX = "TerrakubeV2_ModuleRefresh_";

    private final Scheduler scheduler;

    @SuppressWarnings("deprecation")
    @EventListener(ApplicationReadyEvent.class)
    public void deleteLegacyJobs() {
        Set<JobKey> jobKeys;
        try {
            jobKeys = scheduler.getJobKeys(GroupMatcher.anyJobGroup());
        } catch (SchedulerException e) {
            log.error("Failed to list jobs for the legacy module refresh cleanup, error {}", e.getMessage());
            return;
        }
        int deleted = 0;
        for (JobKey jobKey : jobKeys) {
            if (!jobKey.getName().startsWith(LEGACY_JOB_PREFIX)) {
                continue;
            }
            try {
                JobDetail jobDetail = scheduler.getJobDetail(jobKey);
                if (jobDetail != null && jobDetail.getJobClass() == ModuleRefreshJob.class && scheduler.deleteJob(jobKey)) {
                    deleted++;
                }
            } catch (SchedulerException e) {
                log.warn("Failed to delete legacy module refresh job {}, error {}", jobKey, e.getMessage());
            }
        }
        log.info("Deleted {} legacy module refresh job(s)", deleted);
    }
}
