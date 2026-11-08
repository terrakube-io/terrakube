package io.terrakube.api.plugin.policy;

import io.terrakube.api.plugin.notification.payload.NotificationContext;
import io.terrakube.api.plugin.notification.payload.NotificationPayloadRenderer;
import io.terrakube.api.repository.NotificationOutboxRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.notification.NotificationMessageStyle;
import io.terrakube.api.rs.notification.NotificationOutbox;
import io.terrakube.api.rs.notification.NotificationOutboxStatus;
import io.terrakube.api.rs.policy.PolicySet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class PolicyNotificationService {

    private final NotificationOutboxRepository notificationOutboxRepository;
    private final NotificationPayloadRenderer notificationPayloadRenderer;
    private final String uiUrl;

    public PolicyNotificationService(
            NotificationOutboxRepository notificationOutboxRepository,
            NotificationPayloadRenderer notificationPayloadRenderer,
            @Value("${io.terrakube.ui.url:}") String uiUrl) {
        this.notificationOutboxRepository = notificationOutboxRepository;
        this.notificationPayloadRenderer = notificationPayloadRenderer;
        this.uiUrl = uiUrl;
    }

    public void sendPolicyViolationNotification(Job job, List<PolicySet> violatedPolicySets, String summaryReason) {
        if (job == null || violatedPolicySets == null || violatedPolicySets.isEmpty()) {
            return;
        }

        for (PolicySet policySet : violatedPolicySets) {
            NotificationConfiguration config = policySet.getNotificationConfiguration();
            if (config != null) {
                try {
                    // UI links, built like JobNotificationTrigger (the API host does not serve UI routes).
                    String workspaceUrl = String.format("%s/organizations/%s/workspaces/%s", uiUrl,
                            job.getOrganization().getId(), job.getWorkspace().getId());
                    String runUrl = workspaceUrl + "/runs/" + job.getId();

                    NotificationContext context = new NotificationContext(
                            job.getOrganization().getName(),
                            job.getWorkspace().getName(),
                            job.getId(),
                            job.getStatus() != null ? job.getStatus() : JobStatus.waitingApproval,
                            runUrl,
                            job.getCommitId(),
                            summaryReason,
                            config.getName(),
                            workspaceUrl,
                            NotificationMessageStyle.DETAILED
                    );

                    String payload = notificationPayloadRenderer.render(config.getChannelType(), context);

                    NotificationOutbox outbox = new NotificationOutbox();
                    outbox.setId(UUID.randomUUID());
                    outbox.setJob(job);
                    outbox.setConfiguration(config);
                    outbox.setPayload(payload);
                    outbox.setStatus(NotificationOutboxStatus.PENDING);
                    outbox.setJobStatus(job.getStatus());
                    outbox.setAttemptCount(0);
                    outbox.setLastAttemptAt(null);

                    notificationOutboxRepository.save(outbox);
                    log.info("Enqueued policy violation notification for PolicySet {} Job {} to config {}",
                            policySet.getName(), job.getId(), config.getName());
                } catch (Exception e) {
                    log.error("Failed to enqueue policy notification for PolicySet {}: {}", policySet.getName(), e.getMessage());
                }
            }
        }
    }
}
