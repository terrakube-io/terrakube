package io.terrakube.executor.service.logs;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@Slf4j
@AllArgsConstructor
@ConditionalOnProperty(name = "io.executor.log-via-api", havingValue = "false", matchIfMissing = true)
public class LogsServiceRedis implements ProcessLogs {

    RedisTemplate redisTemplate;

    ExecutorLogsProperties properties;

    private XAddOptions trimOptions() {
        return XAddOptions.maxlen(properties.getRedisMaxLen()).approximateTrimming(true);
    }

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

    // A Redis blip must never abort the terraform run it's logging: this is invoked as a
    // Consumer<String> callback from inside the terraform-spring-boot-starter's process-output
    // reading loop, and an uncaught exception here propagates back through that loop and kills
    // the whole run. A missed log line costs the user some output; a killed apply costs far more.
    @Override
    public void sendLogs(Integer jobId, String stepId, int lineNumber, String output) {
        try {
            Map<String, String> streamData = new LinkedHashMap();
            streamData.put("jobId", String.valueOf(jobId));
            streamData.put("stepId", stepId);
            streamData.put("lineNumber", String.valueOf(lineNumber));
            streamData.put("output", output);

            redisTemplate.opsForStream().add(jobId.toString(), streamData, trimOptions());
        } catch (Exception ex) {
            log.error("Could not send log line to Redis for Job {}: {}", jobId, ex.getMessage());
        }
    }

    @Override
    public void sendStructuredUpdate(Integer jobId, String stepId, String structuredJson) {
        try {
            Map<String, String> streamData = new LinkedHashMap();
            streamData.put("jobId", String.valueOf(jobId));
            streamData.put("stepId", stepId);
            streamData.put("output", structuredJson);

            redisTemplate.opsForStream().add(jobId + "-context", streamData, trimOptions());
        } catch (Exception ex) {
            log.error("Could not send structured update to Redis for Job {}: {}", jobId, ex.getMessage());
        }
    }

    public void deleteLogs(String jobId) {
        redisTemplate.delete(jobId);
    }
}