package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.scheduler.dependency.WorkspaceDependencyService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.WorkspaceDependencyRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.dependency.WorkspaceDependency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceDependencyServiceTests {

    private WorkspaceDependencyRepository dependencyRepository;
    private JobRepository jobRepository;
    private ScheduleJobService scheduleJobService;
    private WorkspaceDependencyService service;

    private Organization organization;
    private Workspace producer;
    private Workspace consumer;

    @BeforeEach
    void setUp() {
        dependencyRepository = mock(WorkspaceDependencyRepository.class);
        jobRepository = mock(JobRepository.class);
        scheduleJobService = mock(ScheduleJobService.class);
        service = new WorkspaceDependencyService(dependencyRepository, jobRepository, scheduleJobService);
        ReflectionTestUtils.setField(service, "maxCascadeDepth", 5);
        ReflectionTestUtils.setField(service, "enabled", true);

        organization = new Organization();
        organization.setId(UUID.randomUUID());

        producer = workspace("vpc", "Plan and apply");
        consumer = workspace("vpn-ipsec", "Plan and apply");

        when(jobRepository.save(any(Job.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Workspace workspace(String name, String defaultTemplate) {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setName(name);
        workspace.setOrganization(organization);
        workspace.setDefaultTemplate(defaultTemplate);
        return workspace;
    }

    private WorkspaceDependency edge(Workspace from, Workspace dependsOn) {
        WorkspaceDependency dependency = new WorkspaceDependency();
        dependency.setId(UUID.randomUUID());
        dependency.setWorkspace(from);
        dependency.setDependsOn(dependsOn);
        return dependency;
    }

    private Job completedJobOn(Workspace workspace, Integer depth) {
        Job job = new Job();
        job.setId(1);
        job.setWorkspace(workspace);
        job.setOrganization(organization);
        job.setDependencyDepth(depth);
        return job;
    }

    @Test
    void triggersARunOnTheConsumerWhenTheProducerApplies() throws Exception {
        when(dependencyRepository.findByDependsOnId(producer.getId()))
                .thenReturn(List.of(edge(consumer, producer)));

        service.triggerDependents(completedJobOn(producer, null));

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        Job triggered = captor.getValue();

        assertEquals(consumer, triggered.getWorkspace(), "run must be created on the consumer");
        assertEquals("Plan and apply", triggered.getTemplateReference(), "should fall back to the consumer default template");
        assertEquals(1, triggered.getDependencyDepth(), "first hop is depth 1");
        verify(scheduleJobService).createJobContext(any(Job.class));
    }

    @Test
    void doesNothingWhenNobodyDependsOnTheProducer() {
        when(dependencyRepository.findByDependsOnId(producer.getId())).thenReturn(List.of());

        service.triggerDependents(completedJobOn(producer, null));

        verify(jobRepository, never()).save(any(Job.class));
    }

    /**
     * A cyclic graph (a -> b -> a) would otherwise trigger runs forever. Once a job carries
     * the maximum depth, the chain stops instead of cascading again.
     */
    @Test
    void stopsCascadingOnceTheDepthLimitIsReached() {
        when(dependencyRepository.findByDependsOnId(producer.getId()))
                .thenReturn(List.of(edge(consumer, producer)));

        service.triggerDependents(completedJobOn(producer, 5));

        verify(jobRepository, never()).save(any(Job.class));
    }

    /** The same consumer reachable twice should still get a single run per apply. */
    @Test
    void triggersTheSameConsumerOnlyOncePerApply() {
        when(dependencyRepository.findByDependsOnId(producer.getId()))
                .thenReturn(List.of(edge(consumer, producer), edge(consumer, producer)));

        service.triggerDependents(completedJobOn(producer, null));

        verify(jobRepository, times(1)).save(any(Job.class));
    }

    /** A workspace pending deletion must not be woken up by its dependency. */
    @Test
    void skipsDeletedConsumers() {
        consumer.setDeleted(true);
        when(dependencyRepository.findByDependsOnId(producer.getId()))
                .thenReturn(List.of(edge(consumer, producer)));

        service.triggerDependents(completedJobOn(producer, null));

        verify(jobRepository, never()).save(any(Job.class));
    }

    /** With no template anywhere there is nothing to run; it must not blow up. */
    @Test
    void skipsConsumerWithoutTemplate() {
        consumer.setDefaultTemplate(null);
        when(dependencyRepository.findByDependsOnId(producer.getId()))
                .thenReturn(List.of(edge(consumer, producer)));

        service.triggerDependents(completedJobOn(producer, null));

        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    void doesNothingWhenDisabled() {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.triggerDependents(completedJobOn(producer, null));

        verify(dependencyRepository, never()).findByDependsOnId(any());
    }
}
