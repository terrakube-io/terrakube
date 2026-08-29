package io.terrakube.api.plugin.scheduler.logs;

import io.terrakube.api.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Collection;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeleteJobLogStreamJobTest {

    @Mock
    JobRepository jobRepository;

    @Mock
    RedisTemplate<String, Object> redisTemplate;

    private DeleteJobLogStreamJob job() {
        return new DeleteJobLogStreamJob(jobRepository, redisTemplate, 30, 24);
    }

    @Test
    void deletesBothStreamsForEachSweptJob() {
        when(jobRepository.findTerminalJobIdsUpdatedBetween(any(Date.class), any(Date.class)))
                .thenReturn(List.of(1, 2));

        job().run();

        verify(redisTemplate).delete(argThat((Collection<String> keys) ->
                keys.contains("1") && keys.contains("1-context")));
        verify(redisTemplate).delete(argThat((Collection<String> keys) ->
                keys.contains("2") && keys.contains("2-context")));
    }

    @Test
    void aRedisFailureDoesNotAbortTheSweep() {
        when(jobRepository.findTerminalJobIdsUpdatedBetween(any(Date.class), any(Date.class)))
                .thenReturn(List.of(1, 3));
        when(redisTemplate.delete(any(Collection.class))).thenThrow(new RuntimeException("blip"));

        job().run(); // must not throw
    }

    @Test
    void aQueryFailureDoesNotAbortTheSweep() {
        when(jobRepository.findTerminalJobIdsUpdatedBetween(any(Date.class), any(Date.class)))
                .thenThrow(new RuntimeException("db down"));

        job().run(); // must not throw
        verify(redisTemplate, never()).delete(any(Collection.class));
    }

    @Test
    void noJobsIsANoOp() {
        when(jobRepository.findTerminalJobIdsUpdatedBetween(any(Date.class), any(Date.class)))
                .thenReturn(List.of());

        job().run();

        verify(redisTemplate, never()).delete(any(Collection.class));
    }
}
