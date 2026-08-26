package io.terrakube.api.plugin.subscription;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
@Slf4j
public class JobStatusPublisher {

    RedisTemplate<String, JobStatusEvent> jobStatusRedisTemplate;

    // Publishing this event is a best-effort UI nicety (live status updates) - a Redis blip here must never
    // fail the job create/update transaction this is called from inside JobManageHook's POSTCOMMIT hook.
    public void publish(JobStatusEvent event, String organizationId) {
        try {
            jobStatusRedisTemplate.convertAndSend(channelFor(event.workspaceId()), event);
            jobStatusRedisTemplate.convertAndSend(organizationChannelFor(organizationId), event);
        } catch (Exception e) {
            log.error("Failed to publish job status event {}: {}", event, e.getMessage());
        }
    }

    public static String channelFor(String workspaceId) {
        return "job-status:" + workspaceId;
    }

    public static String organizationChannelFor(String organizationId) {
        return "org-job-status:" + organizationId;
    }
}
