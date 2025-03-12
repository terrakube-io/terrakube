package io.terrakube.api.plugin.scheduler.module;

import org.quartz.Job;
import org.springframework.stereotype.Service;
import io.terrakube.api.plugin.scheduler.ScheduleServiceBase;

import lombok.Getter;

@Service
@Getter
public class ModuleRefreshService extends ScheduleServiceBase {
    private final String jobPrefix = "TerrakubeV2_ModuleRefresh_";
    private final Class<? extends Job> jobClass = ModuleRefreshJob.class;
    private final String jobType = "ModuleRefresh";
    private final String jobDataKey = "moduleId";
}
