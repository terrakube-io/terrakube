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
import java.util.Objects;
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
                    WorkspaceJobStatusService.Shown shown = workspaceJobStatusService.shownFor(job);
                    Date date = shown.date();
                    // Only the rows that are not right yet are written: on a database that is already
                    // correct, a restart changes nothing.
                    if (workspace.getLastJobStatus() != shown.status() || !Objects.equals(workspace.getLastJobDate(), date)) {
                        workspace.setLastJobStatus(shown.status());
                        workspace.setLastJobDate(date);
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
}
