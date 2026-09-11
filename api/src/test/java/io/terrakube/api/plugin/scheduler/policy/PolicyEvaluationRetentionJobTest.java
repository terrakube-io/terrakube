package io.terrakube.api.plugin.scheduler.policy;

import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.PolicyEvaluationRepository;
import io.terrakube.api.repository.PolicyOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyEvaluationRetentionJobTest {

    private PolicyEvaluationRepository policyEvaluationRepository;
    private PolicyOverrideRepository policyOverrideRepository;
    private StorageTypeService storageTypeService;
    private PolicyEvaluationRetentionJob job;

    @BeforeEach
    void setUp() {
        policyEvaluationRepository = Mockito.mock(PolicyEvaluationRepository.class);
        policyOverrideRepository = Mockito.mock(PolicyOverrideRepository.class);
        storageTypeService = Mockito.mock(StorageTypeService.class);
        job = new PolicyEvaluationRetentionJob(policyEvaluationRepository, policyOverrideRepository, storageTypeService, 90);
    }

    @Test
    void testExecuteDeletesOlderThanRetentionDaysAndPrunesStorage() throws JobExecutionException {
        JobExecutionContext context = Mockito.mock(JobExecutionContext.class);

        when(policyEvaluationRepository.findStorageUrisByCreatedDateBefore(any(Date.class)))
                .thenReturn(List.of("policy-evaluations/101/violations.json", "policy-evaluations/102/violations.json"));
        when(policyOverrideRepository.deleteByCreatedDateBefore(any(Date.class))).thenReturn(2);
        when(policyEvaluationRepository.deleteByCreatedDateBefore(any(Date.class))).thenReturn(5);

        long beforeRun = System.currentTimeMillis();
        job.execute(context);
        long afterRun = System.currentTimeMillis();

        ArgumentCaptor<Date> dateCaptor = ArgumentCaptor.forClass(Date.class);
        verify(policyOverrideRepository).deleteByCreatedDateBefore(dateCaptor.capture());
        verify(policyEvaluationRepository).deleteByCreatedDateBefore(any(Date.class));
        verify(storageTypeService).deletePolicyEvaluation("policy-evaluations/101/violations.json");
        verify(storageTypeService).deletePolicyEvaluation("policy-evaluations/102/violations.json");

        Date cutoff = dateCaptor.getValue();
        long expectedMin = beforeRun - (90L * 24 * 60 * 60 * 1000L);
        long expectedMax = afterRun - (90L * 24 * 60 * 60 * 1000L);

        assertTrue(cutoff.getTime() >= expectedMin && cutoff.getTime() <= expectedMax);
    }

    @Test
    void testExecuteContinuesPruningEvenIfOneStorageDeleteFails() throws JobExecutionException {
        JobExecutionContext context = Mockito.mock(JobExecutionContext.class);

        when(policyEvaluationRepository.findStorageUrisByCreatedDateBefore(any(Date.class)))
                .thenReturn(List.of("policy-evaluations/201/violations.json", "policy-evaluations/202/violations.json"));
        doThrow(new RuntimeException("S3 connection error"))
                .when(storageTypeService).deletePolicyEvaluation("policy-evaluations/201/violations.json");
        when(policyOverrideRepository.deleteByCreatedDateBefore(any(Date.class))).thenReturn(1);
        when(policyEvaluationRepository.deleteByCreatedDateBefore(any(Date.class))).thenReturn(2);

        job.execute(context);

        verify(storageTypeService).deletePolicyEvaluation("policy-evaluations/201/violations.json");
        verify(storageTypeService).deletePolicyEvaluation("policy-evaluations/202/violations.json");
        verify(policyOverrideRepository).deleteByCreatedDateBefore(any(Date.class));
        verify(policyEvaluationRepository).deleteByCreatedDateBefore(any(Date.class));
    }
}
