package io.terrakube.api.plugin.streaming;

import io.terrakube.api.plugin.logs.LogsProperties;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobStatusCacheTest {

    @Mock
    JobRepository jobRepository;

    private JobStatusCache cacheWithTtl(Duration ttl) {
        LogsProperties props = new LogsProperties();
        props.setJobStatusCacheTtl(ttl);
        return new JobStatusCache(jobRepository, props);
    }

    @Test
    void cachesWithinTtlSoRepeatedCallsHitTheDbOnce() {
        Job running = new Job();
        running.setId(5);
        running.setStatus(JobStatus.running);
        when(jobRepository.findById(5)).thenReturn(Optional.of(running));

        JobStatusCache cache = cacheWithTtl(Duration.ofSeconds(60));
        assertFalse(cache.isTerminal("5"));
        assertFalse(cache.isTerminal("5"));
        assertFalse(cache.isTerminal("5"));

        verify(jobRepository, times(1)).findById(5);
    }

    @Test
    void terminalStatusIsReported() {
        Job done = new Job();
        done.setId(6);
        done.setStatus(JobStatus.completed);
        when(jobRepository.findById(6)).thenReturn(Optional.of(done));

        assertTrue(cacheWithTtl(Duration.ofSeconds(60)).isTerminal("6"));
    }

    @Test
    void missingJobIsTreatedAsTerminal() {
        when(jobRepository.findById(7)).thenReturn(Optional.empty());

        assertTrue(cacheWithTtl(Duration.ofSeconds(60)).isTerminal("7"));
    }
}
