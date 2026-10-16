package io.terrakube.api.plugin.scheduler.spool;

import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpoolRecoveryJobTest {

    @Test
    void executeCallsRecoverPendingSpooledSteps() throws JobExecutionException {
        SpoolRecoveryService spoolRecoveryService = mock(SpoolRecoveryService.class);
        JobExecutionContext context = mock(JobExecutionContext.class);

        when(spoolRecoveryService.recoverPendingSpooledSteps()).thenReturn(2);

        SpoolRecoveryJob job = new SpoolRecoveryJob(spoolRecoveryService);
        job.execute(context);

        verify(spoolRecoveryService).recoverPendingSpooledSteps();
    }
}
