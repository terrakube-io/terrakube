package io.terrakube.api.plugin.init;

import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@AllArgsConstructor
@Service
@Slf4j
public class InitWorkspaceStatus {

    private final OrganizationRepository organizationRepository;
    private final WorkspaceRepository workspaceRepository;
    private final JobRepository jobRepository;

    @PostConstruct
    @Transactional
    public void initWorkspaceStatus() {
        organizationRepository.findAll().forEach(organization -> {
            Optional<List<Workspace>> listOptional = workspaceRepository.findWorkspacesByOrganization(organization);
            listOptional.ifPresent(workspaces -> workspaces.forEach(workspace -> {
                Optional<Job> lastJob = jobRepository.findFirstByWorkspaceOrderByIdDesc(workspace);
                if( lastJob.isPresent()){
                    Job job = lastJob.get();
                    workspace.setLastJobStatus(job.getStatus());
                    workspace.setLastJobDate(job.getUpdatedDate());
                    workspaceRepository.save(workspace);
                } else {
                    workspace.setLastJobStatus(JobStatus.NeverExecuted);
                    workspaceRepository.save(workspace);
                    log.warn("Workspace {} has no jobs to update status", workspace.getName());
                }
            }));
        });
    }
}
