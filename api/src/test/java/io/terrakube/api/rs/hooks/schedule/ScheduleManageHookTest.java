package io.terrakube.api.rs.hooks.schedule;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.rs.workspace.schedule.Schedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduleManageHookTest {

    @Mock
    ScheduleJobService scheduleJobService;

    @Mock
    RequestScope requestScope;

    @InjectMocks
    ScheduleManageHook hook;

    private Schedule schedule;
    private String scheduleId;

    @BeforeEach
    void setUp() {
        schedule = new Schedule();
        schedule.setId(UUID.randomUUID());
        schedule.setCron("0 15 6 * * ?");
        scheduleId = schedule.getId().toString();
    }

    private void run(Operation operation) {
        hook.execute(operation, TransactionPhase.POSTCOMMIT, schedule, requestScope, Optional.empty());
    }

    @Test
    void createSchedulesTrigger() throws Exception {
        run(Operation.CREATE);

        verify(scheduleJobService).createJobTrigger("0 15 6 * * ?", scheduleId);
        verify(scheduleJobService, never()).deleteJobTrigger(anyString());
    }

    @Test
    void updateWithNewCronReplacesTrigger() throws Exception {
        schedule.setCron("0 43 6 * * ?");

        run(Operation.UPDATE);

        InOrder inOrder = inOrder(scheduleJobService);
        inOrder.verify(scheduleJobService).deleteJobTrigger(scheduleId);
        inOrder.verify(scheduleJobService).createJobTrigger("0 43 6 * * ?", scheduleId);
    }

    @Test
    void updateDisabledScheduleOnlyRemovesTrigger() throws Exception {
        schedule.setEnabled(false);

        run(Operation.UPDATE);

        verify(scheduleJobService).deleteJobTrigger(scheduleId);
        verify(scheduleJobService, never()).createJobTrigger(anyString(), anyString());
    }

    @Test
    void updateReEnabledScheduleRecreatesTrigger() throws Exception {
        schedule.setEnabled(true);

        run(Operation.UPDATE);

        verify(scheduleJobService).createJobTrigger("0 15 6 * * ?", scheduleId);
    }

    @Test
    void deleteRemovesTrigger() throws Exception {
        run(Operation.DELETE);

        verify(scheduleJobService).deleteJobTrigger(scheduleId);
        verify(scheduleJobService, never()).createJobTrigger(anyString(), anyString());
    }
}
