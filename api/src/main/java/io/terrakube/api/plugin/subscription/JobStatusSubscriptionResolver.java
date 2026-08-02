package io.terrakube.api.plugin.subscription;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.AllArgsConstructor;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@Controller
@AllArgsConstructor
public class JobStatusSubscriptionResolver {

    RedisMessageListenerContainer redisMessageListenerContainer;
    RedisSerializer<JobStatusEvent> jobStatusEventSerializer;
    WorkspaceRepository workspaceRepository;
    OrganizationRepository organizationRepository;
    MembershipService membershipService;
    AuthenticatedUser authenticatedUser;

    @SubscriptionMapping
    public Flux<JobStatusEvent> jobStatusChanged(@Argument String workspaceId, Authentication authentication) {
        return jobStatusChanged(workspaceId, new User((Principal) authentication));
    }

    @SubscriptionMapping
    public Flux<JobStatusEvent> organizationJobStatusChanged(@Argument String organizationId, Authentication authentication) {
        return organizationJobStatusChanged(organizationId, new User((Principal) authentication));
    }

    Flux<JobStatusEvent> jobStatusChanged(String workspaceId, User user) {
        Workspace workspace = workspaceRepository.findById(UUID.fromString(workspaceId)).orElse(null);
        if (workspace == null) {
            return Flux.error(new SecurityException("Workspace not found"));
        }

        if (!isAuthorized(user, workspace.getOrganization().getTeam())) {
            return Flux.error(new SecurityException("Not authorized to view this workspace"));
        }

        return listenOn(JobStatusPublisher.channelFor(workspaceId));
    }

    Flux<JobStatusEvent> organizationJobStatusChanged(String organizationId, User user) {
        Organization organization = organizationRepository.findById(UUID.fromString(organizationId)).orElse(null);
        if (organization == null) {
            return Flux.error(new SecurityException("Organization not found"));
        }

        if (!isAuthorized(user, organization.getTeam())) {
            return Flux.error(new SecurityException("Not authorized to view this organization"));
        }

        return listenOn(JobStatusPublisher.organizationChannelFor(organizationId));
    }

    private boolean isAuthorized(User user, List<Team> teams) {
        return authenticatedUser.isSuperUser(user) || membershipService.checkMembership(user, teams);
    }

    private Flux<JobStatusEvent> listenOn(String channel) {
        ChannelTopic topic = new ChannelTopic(channel);

        return Flux.create(sink -> {
            MessageListener listener = (message, pattern) ->
                    sink.next(jobStatusEventSerializer.deserialize(message.getBody()));

            redisMessageListenerContainer.addMessageListener(listener, topic);
            sink.onDispose(() -> redisMessageListenerContainer.removeMessageListener(listener, topic));
        });
    }
}
