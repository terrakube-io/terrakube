package io.terrakube.api.plugin.subscription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JobStatusPublisherTest {

    @Mock
    RedisTemplate<String, JobStatusEvent> jobStatusRedisTemplate;

    @Test
    void publishesToTheWorkspaceScopedChannel() {
        JobStatusPublisher publisher = new JobStatusPublisher(jobStatusRedisTemplate);
        JobStatusEvent event = new JobStatusEvent(42, "workspace-1", "running");

        publisher.publish(event, "org-1");

        verify(jobStatusRedisTemplate).convertAndSend("job-status:workspace-1", event);
    }

    @Test
    void publishesToTheOrganizationScopedChannel() {
        JobStatusPublisher publisher = new JobStatusPublisher(jobStatusRedisTemplate);
        JobStatusEvent event = new JobStatusEvent(42, "workspace-1", "running");

        publisher.publish(event, "org-1");

        verify(jobStatusRedisTemplate).convertAndSend("org-job-status:org-1", event);
    }

    @Test
    void channelForIncludesTheWorkspaceId() {
        assertThat(JobStatusPublisher.channelFor("workspace-1")).isEqualTo("job-status:workspace-1");
    }

    @Test
    void organizationChannelForIncludesTheOrganizationId() {
        assertThat(JobStatusPublisher.organizationChannelFor("org-1")).isEqualTo("org-job-status:org-1");
    }

    @Test
    void doesNotPropagateWhenRedisIsUnavailable() {
        doThrow(new RedisConnectionFailureException("no connection"))
                .when(jobStatusRedisTemplate).convertAndSend("job-status:workspace-1", new JobStatusEvent(42, "workspace-1", "running"));

        JobStatusPublisher publisher = new JobStatusPublisher(jobStatusRedisTemplate);

        assertThatCode(() -> publisher.publish(new JobStatusEvent(42, "workspace-1", "running"), "org-1"))
                .doesNotThrowAnyException();
    }
}
