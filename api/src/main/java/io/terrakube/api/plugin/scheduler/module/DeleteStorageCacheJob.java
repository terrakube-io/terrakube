package io.terrakube.api.plugin.scheduler.module;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;
import io.terrakube.api.plugin.storage.StorageTypeService;

@Slf4j
@AllArgsConstructor
@Component
public class DeleteStorageCacheJob implements Job {

    public static final String JOB_PREFIX = "TerrakubeV2_ModuleDeleteStorage_";

    StorageTypeService storageTypeService;

    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        log.info("Deleting module storage backend data....");
        storageTypeService.deleteModuleStorage(
                jobExecutionContext.getJobDetail().getJobDataMap().getString("moduleOrganization"),
                jobExecutionContext.getJobDetail().getJobDataMap().getString("moduleName"),
                jobExecutionContext.getJobDetail().getJobDataMap().getString("moduleProvider"));
    }
}
