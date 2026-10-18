package io.terrakube.api.plugin.logs;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StreamOperations;
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
        if (logs.isEmpty()) {
            return;
        }
        XAddOptions trim = XAddOptions.maxlen(properties.getRedisMaxLen()).approximateTrimming(true);
        if (redisTemplate.getConnectionFactory() instanceof JedisConnectionFactory factory
                && factory.isRedisClusterAware()) {
            // Jedis cluster connections can't pipeline, so cluster mode keeps one round trip per line.
            addAll(redisTemplate.opsForStream(), logs, trim);
            return;
        }
        // One pipelined round trip for the whole batch instead of one per line.
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                addAll(operations.opsForStream(), logs, trim);
                return null;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void addAll(StreamOperations streamOps, List<Log> logs, XAddOptions trim) {
        for (Log log : logs) {
            streamOps.add(log.getJobId().toString(), log.toStrMap(), trim);
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
