package io.terrakube.api.plugin.softdelete;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.stereotype.Service;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.scheduler.module.ModuleRefreshService;
import io.terrakube.api.plugin.scheduler.workspace.DeleteStorageBackendJob;
import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.repository.ScheduleRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.module.Module;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.schedule.Schedule;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@AllArgsConstructor
@Slf4j
@Service
public class SoftDeleteService {

    private static final String PREFIX_JOB_MODULE_DELETE_STORAGE="TerrakubeV2_WorkspaceDeleteStorage";

    ScheduleJobService scheduleJobService;

    ModuleRefreshService moduleRefreshService;

    ModuleRepository moduleRepository;

    ScheduleRepository scheduleRepository;

    WorkspaceRepository workspaceRepository;

    Scheduler scheduler;

    public void disableWorkspaceSchedules(Workspace workspace){
        for(Schedule schedule: workspace.getSchedule()){
            try {
                scheduleJobService.deleteJobTrigger(schedule.getId().toString());
                schedule.setEnabled(false);
                scheduleRepository.save(schedule);
            } catch (ParseException | SchedulerException e) {
                log.error(e.getMessage());
            }
        }

        deleteWorkspaceStorage(workspace);
    }

    public void deleteWorkspaceStorage(Workspace workspace){
        List<Job> jobList = workspace.getJob();
        List<Integer> jobIdList = new ArrayList();
        jobList.forEach(job -> jobIdList.add(job.getId()));
        String workspaceId = workspace.getId().toString();
        String organizationId = workspace.getOrganization().getId().toString();

        try {
            log.info("Setup job to delete storage for organization {} workspace {} jobs {}", organizationId, workspaceId, jobIdList);
            JobDataMap jobDataMap = new JobDataMap();
            jobDataMap.put("organizationId", organizationId);
            jobDataMap.put("workspaceId", workspaceId);
            jobDataMap.put("jobList", jobIdList);

            JobDetail jobDetail = JobBuilder.newJob().ofType(DeleteStorageBackendJob.class)
                    .storeDurably()
                    .setJobData(jobDataMap)
                    .withIdentity(PREFIX_JOB_MODULE_DELETE_STORAGE + "_" + UUID.randomUUID())
                    .withDescription("WorkspaceDeleteStorage")
                    .build();

            Trigger trigger = TriggerBuilder.newTrigger()
                    .startNow()
                    .forJob(jobDetail)
                    .withIdentity(PREFIX_JOB_MODULE_DELETE_STORAGE + "_" + UUID.randomUUID())
                    .withDescription("WorkspaceDeleteStorageV1")
                    .startNow()
                    .build();

            log.info("Creating schedule to delete workspace data: {}", jobDetail.getKey());
            scheduler.scheduleJob(jobDetail, trigger);
        } catch (SchedulerException e) {
            log.error(e.getMessage());
        }
    }

    public void disableOrganization(Organization organization){
        log.info("Disable Organization Id: {}", organization.getId().toString());
        disableOrganizationModules(organization);

        for(Workspace workspace: organization.getWorkspace()){
            log.info("Disable Workspace: {}", workspace.getId().toString());
            disableWorkspaceSchedules(workspace);
            workspace.setDeleted(true);
            workspaceRepository.save(workspace);
        }

    }

    private void disableOrganizationModules(Organization organization) {
        for (Module module : moduleRepository.findByOrganizationId(organization.getId())) {
            try {
                log.info("Disable module refresh schedule for module {}", module.getId());
                moduleRefreshService.deleteTask(module.getId().toString());
            } catch (SchedulerException e) {
                log.error("Failed to delete module refresh task for module {}, error {}", module.getId(),
                        e.getMessage());
            }
        }
    }
}
