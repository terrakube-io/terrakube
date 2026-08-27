package io.terrakube.api;

import io.terrakube.api.plugin.notification.NotificationDispatchService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.NotificationConfigurationRepository;
import io.terrakube.api.repository.NotificationOutboxRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.notification.NotificationChannelType;
import io.terrakube.api.rs.notification.NotificationConfiguration;
import io.terrakube.api.rs.notification.NotificationOutbox;
import io.terrakube.api.rs.notification.NotificationOutboxStatus;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.mockito.Mockito.when;

class NotificationDispatchConcurrencyTest extends ServerApplicationTests {

    @Autowired
    NotificationDispatchService notificationDispatchService;

    @Autowired
    NotificationOutboxRepository notificationOutboxRepository;

    @Autowired
    NotificationConfigurationRepository notificationConfigurationRepository;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    WorkspaceRepository workspaceRepository;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        wireMockServer.resetAll();
    }

    @Test
    void twoConcurrentAttemptsOnTheSameOutboxRowOnlyDeliverOnce() throws Exception {
        wireMockServer.stubFor(post(urlPathEqualTo("/hook"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(200)));

        Organization organization = organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace workspace = workspaceRepository
                .findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();

        NotificationConfiguration configuration = new NotificationConfiguration();
        configuration.setName("Concurrency Test Config");
        configuration.setOrganization(organization);
        configuration.setChannelType(NotificationChannelType.WEBHOOK);
        configuration.setDestinationUrl("http://localhost:" + wireMockServer.port() + "/hook");
        configuration.setActive(true);
        configuration = notificationConfigurationRepository.saveAndFlush(configuration);

        Job job = new Job();
        job.setStatus(JobStatus.failed);
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        jobRepository.saveAndFlush(job);

        NotificationOutbox outbox = new NotificationOutbox();
        outbox.setId(UUID.randomUUID());
        outbox.setJob(job);
        outbox.setConfiguration(configuration);
        outbox.setPayload("{}");
        outbox.setStatus(NotificationOutboxStatus.PENDING);
        notificationOutboxRepository.saveAndFlush(outbox);

        // Simulates the real-world race this fix targets: an overlapping poller cycle
        // (or a second pod's poller) attempting the same row while the immediate
        // async dispatch is still in flight.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Future<?> first = pool.submit(() -> {
            bothReady.countDown();
            await(go);
            notificationDispatchService.attemptDelivery(outbox.getId());
        });
        Future<?> second = pool.submit(() -> {
            bothReady.countDown();
            await(go);
            notificationDispatchService.attemptDelivery(outbox.getId());
        });

        bothReady.await(5, TimeUnit.SECONDS);
        go.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        wireMockServer.verify(1, postRequestedFor(urlPathEqualTo("/hook")));

        NotificationOutbox persisted = notificationOutboxRepository.findById(outbox.getId()).orElseThrow();
        assertEqualsSent(persisted);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void assertEqualsSent(NotificationOutbox outbox) {
        org.assertj.core.api.Assertions.assertThat(outbox.getStatus()).isEqualTo(NotificationOutboxStatus.SENT);
        org.assertj.core.api.Assertions.assertThat(outbox.getAttemptCount()).isEqualTo(1);
    }
}
