package io.terrakube.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.security.audit.AuditActorContext;
import io.terrakube.api.plugin.vcs.WebhookResult;
import io.terrakube.api.plugin.vcs.WebhookService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.repository.WebhookEventRepository;
import io.terrakube.api.repository.WebhookRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.webhook.WebhookEventType;

// Invoke the real transactional service and repository; reload only after the outer commit.
// Provider I/O, webhook lookups and scheduling are isolated from this database regression.
class WebhookJobAuditIntegrationTest extends ServerApplicationTests {

    @Autowired
    WebhookService webhookService;

    @MockitoBean
    WebhookRepository webhookRepository;

    @MockitoBean
    WebhookEventRepository eventRepository;

    @MockitoBean
    GitHubWebhookService gitHubWebhookService;

    @MockitoBean
    ScheduleJobService scheduling;

    private final UUID webhookId = UUID.randomUUID();
    private boolean committedOutsideActorScope;

    @BeforeEach
    void prepareWebhook() throws Exception {
        var workspace = workspaceRepository
                .findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();
        workspace.setOrganization(organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow());
        Vcs vcs = new Vcs();
        vcs.setVcsType(VcsType.GITHUB);
        workspace.setVcs(vcs);
        Webhook webhook = new Webhook();
        webhook.setWorkspace(workspace);
        WebhookEvent event = new WebhookEvent();
        event.setEvent(WebhookEventType.PUSH);
        event.setBranch(".*");
        event.setPath(".*");
        event.setTemplateId("audit-test-template");
        when(webhookRepository.findById(webhookId)).thenReturn(java.util.Optional.of(webhook));
        when(webhookRepository.getReferenceById(webhookId)).thenReturn(webhook);
        when(eventRepository.findByWebhookAndEventOrderByPriorityAsc(webhook, WebhookEventType.PUSH))
                .thenReturn(List.of(event));
        committedOutsideActorScope = false;
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(AuditActorContext.currentActor()).isEmpty();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    assertThat(AuditActorContext.currentActor()).isEmpty();
                    committedOutsideActorScope = true;
                }
            });
            return null;
        }).when(scheduling).createJobContext(any());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        assertThat(AuditActorContext.currentActor()).isEmpty();
    }

    @Test
    void polledPushPersistsActorAfterOuterTransactionCommits() throws Exception {
        assertSavedActor("  polled\n\u202E\u2028author\u2029\uDB40\uDC01  ", "polledauthor", false);
    }

    @Test
    void polledPushPersistsLongDisplayNameWithinColumnLimit() throws Exception {
        assertSavedActor("a".repeat(200), "a".repeat(128), false);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n\t\u0000\u0085", "\u202E\u2028\u2029\uDB40\uDC01"})
    void polledPushWithoutNormalizedActorRetainsInternalFallback(String actor) throws Exception {
        assertSavedActor(actor, "Internal", false);
    }

    @Test
    void legacyAnonymousWebhookPreservesSanitizedActor() throws Exception {
        assertSavedActor("  legacy\n\u202E\u2028author\u2029\uDB40\uDC01  ", "legacyauthor", true);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n\t\u0000\u0085", "\u202E\u2028\u2029\uDB40\uDC01"})
    void legacyAnonymousWebhookWithoutNormalizedActorRetainsEmptyFallback(String actor) throws Exception {
        assertSavedActor(actor, null, true);
    }

    private void assertSavedActor(String actor, String expected, boolean anonymous) throws Exception {
        assertThat(AopUtils.isAopProxy(webhookService)).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        WebhookResult result = new WebhookResult();
        result.setEvent("push");
        result.setValid(true);
        result.setCreatedBy(actor);
        result.setBranch("main");
        result.setCommit(UUID.randomUUID().toString());
        result.setFileChanges(List.of("main.tf"));
        if (anonymous) {
            SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                    "test", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
            when(gitHubWebhookService.processWebhook(any(), any(), any(), any())).thenReturn(result);
            webhookService.processWebhook(webhookId.toString(), "payload", Map.of());
        } else {
            assertThat(webhookService.triggerPolledPush(webhookId, result)).isTrue();
        }
        assertThat(committedOutsideActorScope).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var captor = ArgumentCaptor.forClass(Job.class);
        verify(scheduling).createJobContext(captor.capture());
        int id = captor.getValue().getId();
        try {
            Job saved = jobRepository.findById(id).orElseThrow();
            assertThat(saved.getCreatedBy()).isEqualTo(expected);
            assertThat(saved.getUpdatedBy()).isEqualTo(expected);
            // A later update must use its own auditor, after the creator's scope has ended.
            SecurityContextHolder.clearContext();
            saved.setComments("background update");
            jobRepository.saveAndFlush(saved);
            Job updated = jobRepository.findById(id).orElseThrow();
            assertThat(updated.getCreatedBy()).isEqualTo(expected);
            assertThat(updated.getUpdatedBy()).isEqualTo("Internal");
        } finally {
            jobRepository.deleteById(id);
        }
    }
}
