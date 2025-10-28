package io.terrakube.executor.service.logs;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@Slf4j
@AllArgsConstructor
@ConditionalOnProperty(name = "org.executor.log-via-api", havingValue = "false", matchIfMissing = true)
public class LogsServiceRedis implements ProcessLogs {

    RedisTemplate redisTemplate;

    @Override
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

    @Override
    public void sendLogs(Integer jobId, String stepId, int lineNumber, String output) {
        Map<String, String> streamData = new LinkedHashMap();
        streamData.put("jobId", String.valueOf(jobId));
        streamData.put("stepId", stepId);
        streamData.put("lineNumber", String.valueOf(lineNumber));
        streamData.put("output", output);

        redisTemplate.opsForStream().add(jobId.toString(), streamData);
    }

    public void deleteLogs(String jobId) {
        redisTemplate.delete(jobId);
    }
}