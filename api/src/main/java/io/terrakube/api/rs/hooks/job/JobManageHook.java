package io.terrakube.api.rs.hooks.job;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.plugin.subscription.JobStatusEvent;
import io.terrakube.api.plugin.subscription.JobStatusPublisher;
import io.terrakube.api.repository.AddressRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.address.Address;
import io.terrakube.api.rs.job.address.AddressType;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import org.quartz.SchedulerException;

import java.text.ParseException;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@AllArgsConstructor
@Slf4j
public class JobManageHook implements LifeCycleHook<Job> {

    private ScheduleJobService scheduleJobService;
    private WorkspaceRepository workspaceRepository;
    private JobStatusPublisher jobStatusPublisher;
    private AddressRepository addressRepository;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation, LifeCycleHookBinding.TransactionPhase transactionPhase, Job job, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.info("JobCreateHook {}", job.getId());
        try {
            switch (operation){
                case CREATE:
                    createAddresses(job, job.getTargetAddrs(), AddressType.TARGET);
                    createAddresses(job, job.getReplaceAddrs(), AddressType.REPLACE);
                    updateWorkspaceStatus(job);
                    scheduleJobService.createJobContext(job);
                    publishStatus(job);
                    break;
                case UPDATE:
                    updateWorkspaceStatus(job);
                    if(job.getStatus().equals(JobStatus.cancelled)) {
                        scheduleJobService.deleteJobContext(job.getId());
                    } else {
                        if (!job.getStatus().equals(JobStatus.running)) {
                            log.info("Creating new quartz job");
                            scheduleJobService.createJobContextNow(job);
                        } else {
                            log.warn("Skip new quartz job");
                        }
                    }
                    publishStatus(job);
                    break;
                default:
                    log.info("Not supported {}", operation);
                    break;
            }

        } catch (ParseException | SchedulerException e) {
            log.error(e.getMessage());
        }
    }

    private void createAddresses(Job job, List<String> addressNames, AddressType type) {
        if (addressNames == null) {
            return;
        }
        for (String addressName : addressNames) {
            if (addressName == null || addressName.isBlank()) {
                continue;
            }
            Address address = new Address();
            address.setName(addressName.trim());
            address.setType(type);
            address.setJob(job);
            try {
                addressRepository.save(address);
            } catch (RuntimeException e) {
                log.error("Failed to save {} address {} for job {}: {}", type, addressName, job.getId(), e.getMessage());
            }
        }
    }

    private void publishStatus(Job job) {
        jobStatusPublisher.publish(
                new JobStatusEvent(job.getId(), job.getWorkspace().getId().toString(), job.getStatus().name()),
                job.getOrganization().getId().toString());
    }

    private void updateWorkspaceStatus(Job job) {
        log.info("Updating last status for workspace {} to {}", job.getWorkspace().getName(), job.getStatus());
        job.getWorkspace().setLastJobStatus(job.getStatus());
        job.getWorkspace().setLastJobDate(new Date(System.currentTimeMillis()));
        workspaceRepository.save(job.getWorkspace());
    }
}
