package io.terrakube.api.plugin.logs;

import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.streaming.StreamingService;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.step.Step;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StepOutputReaderTest {

    @Mock
    StorageTypeService storageTypeService;
    @Mock
    StreamingService streamingService;

    @InjectMocks
    StepOutputReader subject;

    private Job job() {
        Organization organization = new Organization();
        organization.setId(UUID.randomUUID());
        Job job = new Job();
        job.setId(721);
        job.setOrganization(organization);
        return job;
    }

    private Step step() {
        Step step = new Step();
        step.setId(UUID.randomUUID());
        return step;
    }

    @Test
    void prefersLiveLogsAndStripsAnsi() {
        Job job = job();
        Step step = step();
        when(streamingService.getCurrentLogs(step.getId().toString(), ""))
                .thenReturn("[31mError: quota exceeded[0m");

        assertThat(subject.read(job, step)).isEqualTo("Error: quota exceeded");
        verify(storageTypeService, never()).getStepOutput(anyString(), anyString(), anyString());
    }

    @Test
    void fallsBackToStoredOutputWhenLiveLogsAreEmpty() {
        Job job = job();
        Step step = step();
        when(streamingService.getCurrentLogs(anyString(), anyString())).thenReturn("");
        when(storageTypeService.getStepOutput(job.getOrganization().getId().toString(), "721",
                step.getId().toString())).thenReturn("stored output".getBytes(StandardCharsets.UTF_8));

        assertThat(subject.read(job, step)).isEqualTo("stored output");
    }

    @Test
    void returnsNullWhenNeitherSourceHasContent() {
        Job job = job();
        Step step = step();
        when(streamingService.getCurrentLogs(anyString(), anyString())).thenReturn(null);
        when(storageTypeService.getStepOutput(anyString(), anyString(), anyString())).thenReturn(new byte[0]);

        assertThat(subject.read(job, step)).isNull();
    }

    @Test
    void returnsNullInsteadOfPropagatingAnException() {
        Job job = job();
        Step step = step();
        when(streamingService.getCurrentLogs(anyString(), anyString())).thenThrow(new RuntimeException("boom"));

        assertThat(subject.read(job, step)).isNull();
    }

    @Test
    void stripAnsiToleratesNull() {
        assertThat(subject.stripAnsi(null)).isNull();
    }
}
