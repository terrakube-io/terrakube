package io.terrakube.api.plugin.logs;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.storage.model.ByteRange;
import io.terrakube.api.plugin.storage.model.StepOutputPart;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StepLogServiceTest {

    @Mock StorageTypeService storage;
    @Mock StepRepository stepRepository;

    StepLogService service;
    LogsProperties props;
    SimpleMeterRegistry registry;

    UUID stepId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        props = new LogsProperties();
        props.setCacheMaxWeightBytes(1_000_000);
        props.setCacheTtl(Duration.ofMinutes(10));
        props.setCacheableMaxObjectBytes(1_000_000);
        registry = new SimpleMeterRegistry();
        service = new StepLogService(storage, stepRepository, props, registry);
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
    void storageFailureSurfacesAsStepLogUnavailableAndIsCounted() {
        stepWithStatus(JobStatus.completed);
        when(storage.getStepOutputStream("o", "j", stepId.toString(), null))
                .thenThrow(new RuntimeException("S3 timeout"));

        assertThrows(StepLogUnavailableException.class, () -> service.resolve("o", "j", stepId.toString()));
        assertEquals(1.0, registry.get("terrakube.api.step.log.storage.failures").counter().count());
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

    private void finalObjectMissing() {
        when(storage.getStepOutputStream("o", "j", stepId.toString(), null)).thenReturn(StepOutputStream.missing());
    }

    private void archivedParts(String... contents) throws Exception {
        java.util.List<StepOutputPart> listed = new java.util.ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            String name = String.format("%06d.part", i + 1);
            listed.add(new StepOutputPart(name, contents[i].length()));
            lenient().doAnswer(invocation -> {
                long offset = invocation.getArgument(4);
                long length = invocation.getArgument(5);
                return new ByteArrayInputStream(contents[Integer.parseInt(name.substring(0, 6)) - 1].substring((int) offset, (int) (offset + length)).getBytes());
            }).when(storage).openStepOutputPart(eq("o"), eq("j"), eq(stepId.toString()), eq(name), anyLong(), anyLong());
        }
        when(storage.listStepOutputParts("o", "j", stepId.toString())).thenReturn(listed);
    }

    @Test
    void failedStepWithNoFinalObjectIsServedFromTheArchivedPartsAsIncomplete() throws Exception {
        stepWithStatus(JobStatus.failed);
        finalObjectMissing();
        archivedParts("first part, ", "second part");

        StepLogService.StepLog result = service.resolve("o", "j", stepId.toString());

        assertTrue(result.isExists());
        assertTrue(result.isIncomplete());
        assertEquals("first part, second part", new String(result.getBody()));
    }

    @Test
    void theArchivedPartsAreListedOncePerRequest() throws Exception {
        stepWithStatus(JobStatus.failed);
        finalObjectMissing();
        archivedParts("abc", "def");

        service.resolve("o", "j", stepId.toString());

        verify(storage, times(1)).listStepOutputParts("o", "j", stepId.toString());
    }

    @Test
    void incompleteOutputIsNotCached() throws Exception {
        stepWithStatus(JobStatus.failed);
        finalObjectMissing();
        archivedParts("abc");

        service.resolve("o", "j", stepId.toString());
        service.resolve("o", "j", stepId.toString());

        verify(storage, times(2)).getStepOutputStream("o", "j", stepId.toString(), null);
    }

    @Test
    void largeArchivedPartsAreStreamedNotLoaded() throws Exception {
        stepWithStatus(JobStatus.failed);
        props.setCacheableMaxObjectBytes(4);
        finalObjectMissing();
        archivedParts("0123456789");

        StepLogService.StepLog result = service.resolve("o", "j", stepId.toString());

        assertTrue(result.isIncomplete());
        assertNull(result.getBody());
        assertEquals(10, result.getContentLength());
        try (StepOutputStream out = service.openStream("o", "j", stepId.toString(), ByteRange.parse("bytes=2-4").orElseThrow(), true)) {
            assertEquals("234", new String(out.getContent().readAllBytes()));
            assertEquals("bytes 2-4/10", out.getContentRange());
        }
    }

    @Test
    void finalObjectIsPreferredOverParts() throws Exception {
        stepWithStatus(JobStatus.completed);
        byte[] data = "the whole output".getBytes();
        when(storage.getStepOutputStream("o", "j", stepId.toString(), null))
                .thenReturn(StepOutputStream.of(new ByteArrayInputStream(data), data.length, data.length));

        StepLogService.StepLog result = service.resolve("o", "j", stepId.toString());

        assertFalse(result.isIncomplete());
        assertArrayEquals(data, result.getBody());
        verify(storage, never()).listStepOutputParts(any(), any(), any());
    }

    @Test
    void stepWithNeitherFinalObjectNorPartsIsMissing() {
        stepWithStatus(JobStatus.failed);
        finalObjectMissing();
        when(storage.listStepOutputParts("o", "j", stepId.toString())).thenReturn(java.util.List.of());

        assertFalse(service.resolve("o", "j", stepId.toString()).isExists());
    }

    @Test
    void partsAreNotLookedAtWhileTheStepIsStillTerminalUnknown() {
        when(stepRepository.findById(stepId)).thenReturn(Optional.empty());
        finalObjectMissing();

        assertFalse(service.resolve("o", "j", stepId.toString()).isExists());
        verify(storage, never()).listStepOutputParts(any(), any(), any());
    }
}
