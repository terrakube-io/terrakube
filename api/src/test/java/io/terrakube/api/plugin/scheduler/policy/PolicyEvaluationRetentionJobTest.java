package io.terrakube.api.plugin.scheduler.policy;

import io.terrakube.api.repository.PolicyEvaluationRepository;
import io.terrakube.api.repository.PolicyOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyEvaluationRetentionJobTest {

    private PolicyEvaluationRepository policyEvaluationRepository;
    private PolicyOverrideRepository policyOverrideRepository;
    private PolicyEvaluationRetentionJob job;

    @BeforeEach
    void setUp() {
        policyEvaluationRepository = Mockito.mock(PolicyEvaluationRepository.class);
        policyOverrideRepository = Mockito.mock(PolicyOverrideRepository.class);
        job = new PolicyEvaluationRetentionJob(policyEvaluationRepository, policyOverrideRepository, 90);
    }

    @Test
    void testExecuteDeletesOlderThanRetentionDays() throws JobExecutionException {
        JobExecutionContext context = Mockito.mock(JobExecutionContext.class);

        when(policyOverrideRepository.deleteByCreatedDateBefore(any(Date.class))).thenReturn(2);
        when(policyEvaluationRepository.deleteByCreatedDateBefore(any(Date.class))).thenReturn(5);

        long beforeRun = System.currentTimeMillis();
        job.execute(context);
        long afterRun = System.currentTimeMillis();

        ArgumentCaptor<Date> dateCaptor = ArgumentCaptor.forClass(Date.class);
        verify(policyOverrideRepository).deleteByCreatedDateBefore(dateCaptor.capture());
        verify(policyEvaluationRepository).deleteByCreatedDateBefore(any(Date.class));

        Date cutoff = dateCaptor.getValue();
        long expectedMin = beforeRun - (90L * 24 * 60 * 60 * 1000L);
        long expectedMax = afterRun - (90L * 24 * 60 * 60 * 1000L);

        assertTrue(cutoff.getTime() >= expectedMin && cutoff.getTime() <= expectedMax);
    }
}
