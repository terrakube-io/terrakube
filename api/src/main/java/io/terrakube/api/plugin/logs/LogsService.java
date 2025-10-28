package io.terrakube.api.plugin.logs;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import io.terrakube.api.plugin.state.model.logs.Log;

import java.util.List;

@Service
@Slf4j
@AllArgsConstructor
public class LogsService {

    RedisTemplate redisTemplate;

    public void appendLogs(List<Log> logs) {
        for (Log log : logs) {
            redisTemplate.opsForStream().add(log.getJobId().toString(), log.toStrMap());
        }
    }

    public void setupConsumerGroups(String jobId) {
        try {
            redisTemplate.opsForStream().createGroup(jobId, "CLI");
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
        try {
            redisTemplate.opsForStream().createGroup(jobId, "UI");
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
    }
}