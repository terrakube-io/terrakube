package io.terrakube.api.plugin.logs;

import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.storage.model.StepOutputStream;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StepLogServiceTest {

    @Mock StorageTypeService storage;
    @Mock StepRepository stepRepository;

    StepLogService service;
    LogsProperties props;

    UUID stepId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        props = new LogsProperties();
        props.setCacheMaxWeightBytes(1_000_000);
        props.setCacheTtl(Duration.ofMinutes(10));
        props.setCacheableMaxObjectBytes(1_000_000);
        service = new StepLogService(storage, stepRepository, props);
    }

    private void stepWithStatus(JobStatus status) {
        Step step = new Step();
        step.setId(stepId);
        step.setStatus(status);
        when(stepRepository.findById(stepId)).thenReturn(Optional.of(step));
    }

    @Test
    void terminalStepIsReadOnceThenServedFromCache() {
        stepWithStatus(JobStatus.completed);
        byte[] data = "hello world".getBytes();
        when(storage.getStepOutputStream("o", "j", stepId.toString(), null))
                .thenReturn(StepOutputStream.of(new ByteArrayInputStream(data), data.length, data.length));

        StepLogService.StepLog first = service.resolve("o", "j", stepId.toString());
        StepLogService.StepLog second = service.resolve("o", "j", stepId.toString());

        assertArrayEquals(data, first.getBody());
        assertArrayEquals(data, second.getBody());
        verify(storage, times(1)).getStepOutputStream("o", "j", stepId.toString(), null);
    }

    @Test
    void runningStepReturnsMissingWithoutTouchingStorage() {
        stepWithStatus(JobStatus.running);

        StepLogService.StepLog result = service.resolve("o", "j", stepId.toString());

        assertFalse(result.isExists());
        verify(storage, never()).getStepOutputStream(any(), any(), any(), any());
    }

    @Test
    void terminalStepAboveCapIsNotCachedAndReturnsNullBody() {
        stepWithStatus(JobStatus.completed);
        props.setCacheableMaxObjectBytes(4);
        when(storage.getStepOutputStream("o", "j", stepId.toString(), null))
                .thenReturn(StepOutputStream.of(new ByteArrayInputStream(new byte[10]), 10, 10));

        StepLogService.StepLog result = service.resolve("o", "j", stepId.toString());

        assertTrue(result.isExists());
        assertNull(result.getBody());
    }

    @Test
    void missingObjectForTerminalStepReturnsNotExists() {
        stepWithStatus(JobStatus.completed);
        when(storage.getStepOutputStream("o", "j", stepId.toString(), null))
                .thenReturn(StepOutputStream.missing());

        assertFalse(service.resolve("o", "j", stepId.toString()).isExists());
    }

    @Test
    void unknownStepWithAnExistingObjectIsServedButNotCached() {
        when(stepRepository.findById(stepId)).thenReturn(Optional.empty());
        byte[] data = "legacy path access".getBytes();
        when(storage.getStepOutputStream("o", "j", stepId.toString(), null))
                .thenReturn(StepOutputStream.of(new ByteArrayInputStream(data), data.length, data.length))
                .thenReturn(StepOutputStream.of(new ByteArrayInputStream(data), data.length, data.length));

        StepLogService.StepLog first = service.resolve("o", "j", stepId.toString());
        StepLogService.StepLog second = service.resolve("o", "j", stepId.toString());

        assertArrayEquals(data, first.getBody());
        assertArrayEquals(data, second.getBody());
        verify(storage, times(2)).getStepOutputStream("o", "j", stepId.toString(), null);
    }

    @Test
    void nonUuidStepIdIsServedFromStorage() {
        byte[] data = "SAMPLE".getBytes();
        when(storage.getStepOutputStream("o", "j", "3", null))
                .thenReturn(StepOutputStream.of(new ByteArrayInputStream(data), data.length, data.length));

        StepLogService.StepLog result = service.resolve("o", "j", "3");

        assertArrayEquals(data, result.getBody());
    }
}
