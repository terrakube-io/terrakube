package io.terrakube.api;

import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.storage.controller.TerraformOutputController;
import io.terrakube.api.plugin.storage.model.StepOutputStream;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the API-wide stall: {@code TerraformOutputController.getFile} must not run
 * inside a transaction (it was {@code @Transactional}, holding a HikariCP connection for the whole
 * synchronous S3 read). If {@code @Transactional} is ever re-added to the controller, the autowired
 * bean becomes a transaction proxy and the storage read below observes an active transaction.
 */
class StepLogTransactionBoundaryIntegrationTest extends ServerApplicationTests {

    @MockitoBean
    StorageTypeService storageTypeService;

    @Autowired
    TerraformOutputController terraformOutputController;

    @Test
    void stepLogStorageReadRunsWithoutAnActiveTransaction() {
        Workspace workspace = new Workspace();
        workspace.setName(UUID.randomUUID().toString());
        workspace.setSource("https://github.com/AzBuilder/terrakube-docker-compose.git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.5.0");
        workspace.setOrganization(organizationRepository.getReferenceById(
                UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")));
        workspace = workspaceRepository.save(workspace);

        Job job = new Job();
        job.setOrganization(workspace.getOrganization());
        job.setWorkspace(workspace);
        job.setStatus(JobStatus.completed);
        job = jobRepository.save(job);

        Step step = new Step();
        step.setStepNumber(1);
        step.setName("Terraform Apply");
        step.setStatus(JobStatus.completed);
        step.setJob(job);
        step = stepRepository.save(step);

        AtomicBoolean transactionActiveDuringRead = new AtomicBoolean(true);
        when(storageTypeService.getStepOutputStream(any(), any(), any(), any())).thenAnswer(invocation -> {
            transactionActiveDuringRead.set(TransactionSynchronizationManager.isActualTransactionActive());
            return StepOutputStream.of(new ByteArrayInputStream("apply complete".getBytes()), 14, 14);
        });

        ResponseEntity<?> response = terraformOutputController.getFile(
                workspace.getOrganization().getId().toString(),
                String.valueOf(job.getId()),
                step.getId().toString(),
                null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(transactionActiveDuringRead.get(),
                "step-log storage read ran inside a transaction - getFile must not be @Transactional");

        workspace.setDeleted(true);
        workspaceRepository.save(workspace);
    }
}
