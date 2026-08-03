package io.terrakube.api.plugin.subscription;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.team.Team;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobStatusSubscriptionResolverTest {

    @Mock
    RedisMessageListenerContainer redisMessageListenerContainer;

    @Mock
    WorkspaceRepository workspaceRepository;

    @Mock
    OrganizationRepository organizationRepository;

    @Mock
    MembershipService membershipService;

    @Mock
    AuthenticatedUser authenticatedUser;

    @Mock
    JwtAuthenticationToken principal;

    RedisSerializer<JobStatusEvent> serializer = new Jackson2JsonRedisSerializer<>(JobStatusEvent.class);

    @Test
    void rejectsWhenUserLacksWorkspaceAccess() {
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        Organization organization = new Organization();
        organization.setTeam(Collections.emptyList());
        workspace.setOrganization(organization);

        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));
        User user = new User(principal);
        when(authenticatedUser.isSuperUser(user)).thenReturn(false);
        when(membershipService.checkMembership(eq(user), any())).thenReturn(false);

        JobStatusSubscriptionResolver resolver = new JobStatusSubscriptionResolver(
                redisMessageListenerContainer, serializer, workspaceRepository, organizationRepository, membershipService, authenticatedUser);

        StepVerifier.create(resolver.jobStatusChanged(workspaceId.toString(), user))
                .expectErrorMatches(error -> error instanceof SecurityException)
                .verify(Duration.ofSeconds(2));

        verify(redisMessageListenerContainer, never()).addMessageListener(any(), any(ChannelTopic.class));
    }

    @Test
    void emitsDecodedEventsForAnAuthorizedUserAndUnsubscribesOnCancel() {
        UUID workspaceId = UUID.randomUUID();
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        Organization organization = new Organization();
        Team team = new Team();
        team.setName("developers");
        organization.setTeam(List.of(team));
        workspace.setOrganization(organization);

        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));
        User user = new User(principal);
        when(authenticatedUser.isSuperUser(user)).thenReturn(false);
        when(membershipService.checkMembership(eq(user), eq(List.of(team)))).thenReturn(true);

        JobStatusSubscriptionResolver resolver = new JobStatusSubscriptionResolver(
                redisMessageListenerContainer, serializer, workspaceRepository, organizationRepository, membershipService, authenticatedUser);

        Flux<JobStatusEvent> flux = resolver.jobStatusChanged(workspaceId.toString(), user);

        ArgumentCaptor<MessageListener> listenerCaptor = ArgumentCaptor.forClass(MessageListener.class);

        StepVerifier.create(flux)
                .then(() -> {
                    verify(redisMessageListenerContainer).addMessageListener(listenerCaptor.capture(), eq(new ChannelTopic("job-status:" + workspaceId)));
                    JobStatusEvent event = new JobStatusEvent(42, workspaceId.toString(), "running");
                    byte[] body = serializer.serialize(event);
                    listenerCaptor.getValue().onMessage(new DefaultMessage(("job-status:" + workspaceId).getBytes(), body), null);
                })
                .expectNext(new JobStatusEvent(42, workspaceId.toString(), "running"))
                .thenCancel()
                .verify(Duration.ofSeconds(2));

        verify(redisMessageListenerContainer).removeMessageListener(any(MessageListener.class), eq(new ChannelTopic("job-status:" + workspaceId)));
    }

    @Test
    void rejectsOrganizationSubscriptionWhenUserLacksAccess() {
        UUID organizationId = UUID.randomUUID();
        Organization organization = new Organization();
        organization.setId(organizationId);
        organization.setTeam(Collections.emptyList());

        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));
        User user = new User(principal);
        when(authenticatedUser.isSuperUser(user)).thenReturn(false);
        when(membershipService.checkMembership(eq(user), any())).thenReturn(false);

        JobStatusSubscriptionResolver resolver = new JobStatusSubscriptionResolver(
                redisMessageListenerContainer, serializer, workspaceRepository, organizationRepository, membershipService, authenticatedUser);

        StepVerifier.create(resolver.organizationJobStatusChanged(organizationId.toString(), user))
                .expectErrorMatches(error -> error instanceof SecurityException)
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void emitsOrganizationScopedEventsForAnAuthorizedUser() {
        UUID organizationId = UUID.randomUUID();
        Organization organization = new Organization();
        organization.setId(organizationId);
        Team team = new Team();
        team.setName("developers");
        organization.setTeam(List.of(team));

        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));
        User user = new User(principal);
        when(authenticatedUser.isSuperUser(user)).thenReturn(false);
        when(membershipService.checkMembership(eq(user), eq(List.of(team)))).thenReturn(true);

        JobStatusSubscriptionResolver resolver = new JobStatusSubscriptionResolver(
                redisMessageListenerContainer, serializer, workspaceRepository, organizationRepository, membershipService, authenticatedUser);

        Flux<JobStatusEvent> flux = resolver.organizationJobStatusChanged(organizationId.toString(), user);

        ArgumentCaptor<MessageListener> listenerCaptor = ArgumentCaptor.forClass(MessageListener.class);

        StepVerifier.create(flux)
                .then(() -> {
                    verify(redisMessageListenerContainer).addMessageListener(listenerCaptor.capture(), eq(new ChannelTopic("org-job-status:" + organizationId)));
                    JobStatusEvent event = new JobStatusEvent(42, "workspace-1", "running");
                    byte[] body = serializer.serialize(event);
                    listenerCaptor.getValue().onMessage(new DefaultMessage(("org-job-status:" + organizationId).getBytes(), body), null);
                })
                .expectNext(new JobStatusEvent(42, "workspace-1", "running"))
                .thenCancel()
                .verify(Duration.ofSeconds(2));
    }
}
