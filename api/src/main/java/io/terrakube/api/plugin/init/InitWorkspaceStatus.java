package io.terrakube.api.plugin.init;

import io.terrakube.api.plugin.workspace.WorkspaceJobStatusService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.workspace.Workspace;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@AllArgsConstructor
@Service
@Slf4j
public class InitWorkspaceStatus {

    private final OrganizationRepository organizationRepository;
    private final WorkspaceRepository workspaceRepository;
    private final JobRepository jobRepository;
    private final WorkspaceJobStatusService workspaceJobStatusService;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initWorkspaceStatus() {
        organizationRepository.findAll().forEach(organization -> {
            Optional<List<Workspace>> listOptional = workspaceRepository.findWorkspacesByOrganization(organization);
            listOptional.ifPresent(workspaces -> workspaces.forEach(workspace -> {
                Optional<Job> lastJob = jobRepository.findFirstByWorkspaceOrderByIdDesc(workspace);
                if( lastJob.isPresent()){
                    Job job = lastJob.get();
                    WorkspaceJobStatusService.Shown shown = workspaceJobStatusService.shownFor(job, lastChange(job, workspace));
                    // The same guard WorkspaceJobStatusService.refresh uses: while the status shown is the
                    // stored one and the stored date is at or after the job's creation, the stored date is
                    // the one the live path chose, so a restart writes nothing.
                    Date current = workspace.getLastJobDate();
                    boolean keepDate = workspace.getLastJobStatus() == shown.status() && current != null
                            && job.getCreatedDate() != null && !current.before(job.getCreatedDate());
                    if (!keepDate) {
                        workspace.setLastJobStatus(shown.status());
                        workspace.setLastJobDate(shown.date());
                        workspaceRepository.save(workspace);
                    }
                } else {
                    if (workspace.getLastJobStatus() != JobStatus.NeverExecuted) {
                        workspace.setLastJobStatus(JobStatus.NeverExecuted);
                        workspaceRepository.save(workspace);
                    }
                    log.warn("Workspace {} has no jobs to update status", workspace.getName());
                }
            }));
        });
    }

    private static Date lastChange(Job job, Workspace workspace) {
        if (job.getUpdatedDate() != null) {
            return job.getUpdatedDate();
        }
        if (workspace.getLastJobDate() != null) {
            return workspace.getLastJobDate();
        }
        return new Date(System.currentTimeMillis());
    }
}
