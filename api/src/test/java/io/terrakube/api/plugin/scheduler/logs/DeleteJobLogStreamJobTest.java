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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
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
    void deletesBothStreamsIndividuallyForEachSweptJob() {
        when(jobRepository.findTerminalJobIdsUpdatedBetween(any(Date.class), any(Date.class)))
                .thenReturn(List.of(1, 2));

        job().run();

        verify(redisTemplate).delete("1");
        verify(redisTemplate).delete("1-context");
        verify(redisTemplate).delete("2");
        verify(redisTemplate).delete("2-context");
        verify(redisTemplate, never()).delete(any(Collection.class));
    }

    @Test
    void aRedisFailureDoesNotAbortTheSweepOrSkipContextStream() {
        when(jobRepository.findTerminalJobIdsUpdatedBetween(any(Date.class), any(Date.class)))
                .thenReturn(List.of(1, 3));
        doThrow(new RuntimeException("blip")).when(redisTemplate).delete("1");

        job().run(); // must not throw

        verify(redisTemplate).delete("1-context");
        verify(redisTemplate).delete("3");
        verify(redisTemplate).delete("3-context");
    }

    @Test
    void aQueryFailureDoesNotAbortTheSweep() {
        when(jobRepository.findTerminalJobIdsUpdatedBetween(any(Date.class), any(Date.class)))
                .thenThrow(new RuntimeException("db down"));

        job().run(); // must not throw
        verify(redisTemplate, never()).delete(anyString());
        verify(redisTemplate, never()).delete(any(Collection.class));
    }

    @Test
    void noJobsIsANoOp() {
        when(jobRepository.findTerminalJobIdsUpdatedBetween(any(Date.class), any(Date.class)))
                .thenReturn(List.of());

        job().run();

        verify(redisTemplate, never()).delete(anyString());
        verify(redisTemplate, never()).delete(any(Collection.class));
    }
}
