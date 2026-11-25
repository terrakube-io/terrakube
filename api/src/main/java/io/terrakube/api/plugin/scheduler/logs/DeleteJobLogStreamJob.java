package io.terrakube.api.plugin.scheduler.logs;

import io.terrakube.api.repository.JobRepository;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * Housekeeping sweep: deletes the {@code {jobId}} and {@code {jobId}-context} Redis streams for jobs
 * that have been terminal for at least the grace period. Idempotent - a {@code DEL} of an
 * already-gone key is cheap. A Redis failure never aborts the sweep.
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class DeleteJobLogStreamJob implements Job {

    private final JobRepository jobRepository;
    private final RedisTemplate redisTemplate;
    private final long gracePeriodMillis;
    private final long lookbackMillis;

    public DeleteJobLogStreamJob(
            JobRepository jobRepository,
            RedisTemplate redisTemplate,
            @Value("${io.terrakube.logs.stream-cleanup.grace-period-minutes:30}") long gracePeriodMinutes,
            @Value("${io.terrakube.logs.stream-cleanup.lookback-hours:24}") long lookbackHours) {
        this.jobRepository = jobRepository;
        this.redisTemplate = redisTemplate;
        this.gracePeriodMillis = gracePeriodMinutes * 60_000L;
        this.lookbackMillis = lookbackHours * 3_600_000L;
    }

    @Override
    public void execute(JobExecutionContext context) {
        run();
    }

    void run() {
        long now = System.currentTimeMillis();
        Date cutoff = new Date(now - gracePeriodMillis);
        Date from = new Date(now - gracePeriodMillis - lookbackMillis);

        List<Integer> jobIds;
        try {
            jobIds = jobRepository.findTerminalJobIdsUpdatedBetween(from, cutoff);
        } catch (Exception e) {
            log.error("Job log stream cleanup query failed: {}", e.getMessage());
            return;
        }

        // Delete each stream key individually: Redis Cluster rejects multi-key DEL with CROSSSLOT
        // when {jobId} and {jobId}-context do not share a hash slot.
        int deleted = 0;
        for (Integer jobId : jobIds) {
            boolean jobDeleted = true;
            for (String key : List.of(String.valueOf(jobId), jobId + "-context")) {
                try {
                    redisTemplate.delete(key);
                } catch (Exception e) {
                    jobDeleted = false;
                    log.warn("Redis log stream cleanup failed for job {} key {}: {}", jobId, key, e.getMessage());
                }
            }
            if (jobDeleted) {
                deleted++;
            }
        }

        if (deleted > 0) {
            log.info("Reclaimed Redis log streams for {} completed jobs", deleted);
        }
    }
}
