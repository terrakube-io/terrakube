package io.terrakube.api.plugin.scheduler.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.JobPersistenceException;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Schedules {@link RepoWebhookSyncJob} to reconcile a repository's shared
 * GitHub webhook, keyed deterministically by a hash of the normalized
 * repository URL so concurrent triggers for the same repo coalesce onto a
 * single job execution instead of racing each other's database writes and
 * GitHub API calls.
 *
 * <p>The coalescing relies on Quartz's own job store locking (the same
 * cluster-safe, portable mechanism it uses internally to guarantee only one
 * node fires a given trigger): {@link Scheduler#scheduleJob} either
 * succeeds in creating the job under this key, or throws
 * {@link ObjectAlreadyExistsException} if another caller's job under that
 * same key is already present — nothing here reimplements locking.
 */
@Slf4j
@Service
public class RepoWebhookSyncScheduler {

    public static final String DATA_KEY_REPOSITORY_URL = "repositoryUrl";
    public static final String DATA_KEY_WORKSPACE_ID = "workspaceId";
    static final String JOB_GROUP = "repo-webhook-sync";

    @Autowired
    private Scheduler scheduler;

    /**
     * Requests a sync of the shared webhook for {@code normalizedRepositoryUrl}.
     * {@code workspaceId} seeds a brand-new {@code RepoWebhook} row's VCS
     * connection if one doesn't exist yet; the job re-derives every other
     * affected workspace itself, so this is only a hint, not a requirement
     * for correctness.
     */
    public void scheduleSync(String normalizedRepositoryUrl, String workspaceId) {
        JobKey jobKey = jobKeyFor(normalizedRepositoryUrl);

        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put(DATA_KEY_REPOSITORY_URL, normalizedRepositoryUrl);
        jobDataMap.put(DATA_KEY_WORKSPACE_ID, workspaceId);

        JobDetail jobDetail = JobBuilder.newJob(RepoWebhookSyncJob.class)
                .withIdentity(jobKey)
                .usingJobData(jobDataMap)
                .storeDurably(false)
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .forJob(jobDetail)
                .withIdentity(jobKey.getName(), jobKey.getGroup())
                .startNow()
                .build();

        try {
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("Scheduled repo webhook sync job {} for {}", jobKey, normalizedRepositoryUrl);
        } catch (ObjectAlreadyExistsException e) {
            // Another concurrent caller already scheduled the sync for this
            // exact URL. That job re-queries every workspace currently
            // sharing the URL (including this caller's), so there's
            // nothing more to do here — this is the coalescing, not an
            // error.
            log.info("Repo webhook sync already scheduled for {}, skipping duplicate trigger", normalizedRepositoryUrl);
        } catch (JobPersistenceException e) {
            // Quartz's own scheduleJob() pre-check (the one that throws the
            // clean ObjectAlreadyExistsException above) is a SELECT-then-INSERT
            // that isn't itself atomic under real concurrent transactions —
            // under load, two callers can both pass that pre-check and race
            // on the INSERT, with the loser surfacing this lower-level
            // exception (a raw unique-constraint violation on the job store's
            // own primary key) instead. jobKeyFor() is a deterministic hash of
            // the URL and this job is only ever scheduled from here, so the
            // only way storing under this exact key can fail is a concurrent
            // caller already having inserted it — i.e. the same coalescing
            // outcome as above, just surfaced through a different exception.
            log.info("Repo webhook sync lost a race to schedule for {}, treating as already scheduled", normalizedRepositoryUrl, e);
        } catch (SchedulerException e) {
            throw new IllegalStateException("Failed to schedule repo webhook sync for " + normalizedRepositoryUrl, e);
        }
    }

    static JobKey jobKeyFor(String normalizedRepositoryUrl) {
        return new JobKey(sha256Hex(normalizedRepositoryUrl), JOB_GROUP);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
