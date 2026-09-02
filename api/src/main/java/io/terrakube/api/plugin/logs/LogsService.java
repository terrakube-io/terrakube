package io.terrakube.api.plugin.logs;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import io.terrakube.api.plugin.state.model.logs.Log;

import java.util.List;

@Service
@Slf4j
@AllArgsConstructor
public class LogsService {

    RedisTemplate redisTemplate;

    LogsProperties properties;

    public void appendLogs(List<Log> logs) {
        XAddOptions trim = XAddOptions.maxlen(properties.getRedisMaxLen()).approximateTrimming(true);
        for (Log log : logs) {
            redisTemplate.opsForStream().add(log.getJobId().toString(), log.toStrMap(), trim);
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
