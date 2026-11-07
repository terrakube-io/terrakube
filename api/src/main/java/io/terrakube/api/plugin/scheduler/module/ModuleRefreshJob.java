package io.terrakube.api.plugin.scheduler.module;

import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.SchedulerException;

/**
 * The old per-module refresh job, replaced by {@link ModuleRefreshPoller}. Kept so Quartz can still load
 * the jobs stored before the upgrade: {@link LegacyModuleRefreshJobCleanup} deletes them at startup, and any
 * that fires before that deletes itself here.
 */
@Deprecated
@Slf4j
public class ModuleRefreshJob implements Job {

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            context.getScheduler().deleteJob(context.getJobDetail().getKey());
            log.info("Deleted legacy module refresh job {}", context.getJobDetail().getKey());
        } catch (SchedulerException e) {
            throw new JobExecutionException(e);
        }
    }
}
