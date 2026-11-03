package io.terrakube.api.plugin.scheduler.module;

import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModuleRefreshPollerSchedulerTest {

    private static final TriggerKey KEY = new TriggerKey(ModuleRefreshPollerScheduler.PREFIX_MODULE_REFRESH_POLLER);

    @Test
    void resetsATriggerInErrorState() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.getTriggerState(KEY)).thenReturn(Trigger.TriggerState.ERROR);

        new ModuleRefreshPollerScheduler(scheduler).resetPollerTriggerFromError();

        verify(scheduler).resetTriggerFromErrorState(KEY);
    }

    @Test
    void leavesAHealthyTriggerAlone() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.getTriggerState(KEY)).thenReturn(Trigger.TriggerState.NORMAL);

        new ModuleRefreshPollerScheduler(scheduler).resetPollerTriggerFromError();

        verify(scheduler, never()).resetTriggerFromErrorState(any());
    }
}
