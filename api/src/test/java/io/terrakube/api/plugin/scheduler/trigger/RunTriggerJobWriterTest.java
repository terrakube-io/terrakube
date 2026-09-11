package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The shape of the job a run trigger produces. */
class RunTriggerJobWriterTest {

    JobRepository jobRepository;
    RunTriggerJobWriter subject;

    @BeforeEach
    void setup() {
        jobRepository = mock(JobRepository.class);
        doAnswer(i -> {
            Job saved = i.getArgument(0);
            saved.setId(1001);
            return saved;
        }).when(jobRepository).save(any(Job.class));
        subject = new RunTriggerJobWriter(jobRepository);
    }

    @Test
    void writesTheCanonicalTriggeredJob() {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());

        Workspace destination = new Workspace();
        destination.setId(UUID.randomUUID());
        destination.setName("consumer");
        destination.setOrganization(organization);

        Job completedJob = new Job();
        completedJob.setId(900);

        Job returned = subject.persist(destination, "template-ref", completedJob, 3);

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        Job created = captor.getValue();

        assertThat(returned).isSameAs(created);
        assertThat(created.getWorkspace()).isSameAs(destination);
        assertThat(created.getOrganization()).isSameAs(organization);
        assertThat(created.getTemplateReference()).isEqualTo("template-ref");
        assertThat(created.getStatus()).isEqualTo(JobStatus.pending);
        assertThat(created.getVia()).isEqualTo(JobVia.RUN_TRIGGER.getValue());
        assertThat(created.getTriggeredByJobId()).isEqualTo(900);
        assertThat(created.getCascadeDepth()).isEqualTo(3);
        assertThat(created.isPlanChanges()).isTrue();
        assertThat(created.isRefresh()).isTrue();
        assertThat(created.isRefreshOnly()).isFalse();
        assertThat(created.getCreatedBy()).isEqualTo("serviceAccount");
    }
}
