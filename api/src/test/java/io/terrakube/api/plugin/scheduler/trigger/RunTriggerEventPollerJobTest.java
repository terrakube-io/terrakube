package io.terrakube.api.plugin.scheduler.trigger;

import io.terrakube.api.repository.RunTriggerEventRepository;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEvent;
import io.terrakube.api.rs.workspace.trigger.RunTriggerEventStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.quartz.JobExecutionContext;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RunTriggerEventPollerJobTest {

    @Mock
    RunTriggerEventRepository runTriggerEventRepository;
    @Mock
    RunTriggerEventDispatchService runTriggerEventDispatchService;
    @Mock
    RunTriggerEventTransactions runTriggerEventTransactions;
    @Mock
    JobExecutionContext jobExecutionContext;

    RunTriggerProperties properties;
    RunTriggerEventPollerJob subject;

    @BeforeEach
    void setUp() {
        properties = new RunTriggerProperties();
        subject = new RunTriggerEventPollerJob(runTriggerEventRepository, runTriggerEventDispatchService,
                runTriggerEventTransactions, properties);
    }

    private RunTriggerEvent row() {
        RunTriggerEvent event = new RunTriggerEvent();
        event.setId(UUID.randomUUID());
        event.setStatus(RunTriggerEventStatus.PENDING);
        return event;
    }

    @Test
    void processesEveryRowReturnedAsDue() throws Exception {
        RunTriggerEvent first = row();
        RunTriggerEvent second = row();
        doReturn(List.of(first, second)).when(runTriggerEventRepository)
                .findDueForProcessing(eq(RunTriggerEventStatus.PENDING), any(), any(Pageable.class));

        subject.execute(jobExecutionContext);

        verify(runTriggerEventDispatchService).process(first.getId());
        verify(runTriggerEventDispatchService).process(second.getId());
    }

    @Test
    void sweepsStuckProcessingRowsEveryTick() throws Exception {
        doReturn(List.of()).when(runTriggerEventRepository)
                .findDueForProcessing(any(), any(), any(Pageable.class));

        subject.execute(jobExecutionContext);

        verify(runTriggerEventTransactions).sweepStuckProcessingRows(any(), eq(properties.getEventMaxAttempts()));
    }

    @Test
    void doesNothingWhenTheFeatureIsDisabled() throws Exception {
        properties.setEnabled(false);

        subject.execute(jobExecutionContext);

        verify(runTriggerEventRepository, never()).findDueForProcessing(any(), any(), any());
        verify(runTriggerEventTransactions, never()).sweepStuckProcessingRows(any(), any(Integer.class));
    }

    @Test
    void doesNothingWhenOnlyTheEventWorkerIsDisabled() throws Exception {
        properties.setEventWorkerEnabled(false);

        subject.execute(jobExecutionContext);

        verify(runTriggerEventRepository, never()).findDueForProcessing(any(), any(), any());
        verify(runTriggerEventTransactions, never()).sweepStuckProcessingRows(any(), any(Integer.class));
    }
}
