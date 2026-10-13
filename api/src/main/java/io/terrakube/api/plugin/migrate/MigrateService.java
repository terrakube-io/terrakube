package io.terrakube.api.plugin.migrate;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.HistoryRepository;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.history.History;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@AllArgsConstructor
public class MigrateService {

    private final HistoryRepository historyRepository;
    private WorkspaceRepository workspaceRepository;
    private OrganizationRepository organizationRepository;
    private JobRepository jobRepository;
    private StorageTypeService storageService;

    public boolean migrateWorkspace(String workspaceId, String organizationId) {
        log.info("Migrating workspace {} to organization {}", workspaceId, organizationId);

        // Find the Workspace
        Optional<Workspace> workspaceOptional = workspaceRepository.findById(UUID.fromString(workspaceId));
        if (workspaceOptional.isEmpty()) {
            log.error("Workspace {} not found", workspaceId);
            return false;
        }

        Workspace workspace = workspaceOptional.get();
        String originalOrg = workspace.getOrganization().getId().toString();

        if (originalOrg.equalsIgnoreCase(organizationId)) {
            log.warn("Workspace {} is already in organization {}", workspaceId, organizationId);
            return false;
        }

        // Check if workspace is locked or deleted
        if (workspace.isLocked() || workspace.isDeleted()) {
            log.error("Workspace {} is locked or deleted; cannot migrate", workspaceId);
            return false;
        }

        // Find the target Organization
        Optional<Organization> organizationOptional = organizationRepository.findById(UUID.fromString(organizationId));
        if (organizationOptional.isEmpty()) {
            log.error("Organization {} not found", organizationId);
            return false;
        }

        Organization newOrganization = organizationOptional.get();

        // Update the Workspace's organization
        workspace.setOrganization(newOrganization);
        workspace = workspaceRepository.save(workspace);

        // Log and return success
        log.info("Workspace {} successfully migrated to organization {}", workspaceId, organizationId);

        log.info("Starting job migration....");
        for(Job job: workspace.getJob()){
            job.setOrganization(newOrganization);
            jobRepository.save(job);
            log.info("Job {} successfully migrated to organization {}", job.getId(), organizationId);
        }

        log.info("Migrating history data for workspace {} to organization {}", workspaceId, organizationId);
        for(History history: workspace.getHistory()){
            history.setOutput(history.getOutput().replace(originalOrg, organizationId));
            historyRepository.save(history);
        }

        storageService.migrateToOrganization(originalOrg, workspaceId, organizationId);
        return true;

    }
}
