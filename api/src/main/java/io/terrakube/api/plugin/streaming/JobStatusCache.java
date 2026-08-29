package io.terrakube.api.plugin.streaming;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.terrakube.api.plugin.logs.LogsProperties;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.JobStatus;
import org.springframework.stereotype.Component;

/**
 * Short-TTL cache of {@code jobId -> is the job terminal?}. A job's step-log broadcaster loop checks
 * this after every Redis read to decide whether to stop; the cache keeps that at most one
 * {@code findById} per job per TTL regardless of how many viewers are attached.
 */
@Component
public class JobStatusCache {

    private final JobRepository jobRepository;
    private final Cache<String, Boolean> terminalByJobId;

    public JobStatusCache(JobRepository jobRepository, LogsProperties properties) {
        this.jobRepository = jobRepository;
        this.terminalByJobId = Caffeine.newBuilder()
                .expireAfterWrite(properties.getJobStatusCacheTtl())
                .maximumSize(10_000)
                .build();
    }

    public boolean isTerminal(String jobId) {
        return terminalByJobId.get(jobId, this::loadTerminal);
    }

    private boolean loadTerminal(String jobId) {
        return jobRepository.findById(Integer.parseInt(jobId))
                .map(job -> {
                    JobStatus s = job.getStatus();
                    return s == JobStatus.completed || s == JobStatus.failed || s == JobStatus.cancelled;
                })
                .orElse(true);
    }
}
