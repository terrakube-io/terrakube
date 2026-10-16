package io.terrakube.executor.service.executor;

import io.terrakube.executor.plugin.tfstate.api.RunnerApiClient;
import io.terrakube.executor.service.mode.TerraformJob;

import static org.mockito.Mockito.mock;

public final class JobExecutionWatchdogApiAccess {

    private JobExecutionWatchdogApiAccess() {
    }

    public static void beatAndRelease(RunnerApiClient client) {
        JobExecutionWatchdog watchdog = new JobExecutionWatchdog(event -> { }, 360, null,
                mock(io.terrakube.executor.service.status.UpdateJobStatus.class), client);
        TerraformJob job = new TerraformJob();
        job.setJobId("1");
        watchdog.markBusy(job);
        watchdog.markFree();
    }
}
