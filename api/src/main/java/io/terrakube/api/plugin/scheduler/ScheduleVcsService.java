package io.terrakube.api.plugin.scheduler;

import org.quartz.Job;
import org.springframework.stereotype.Service;

import lombok.Getter;

@Deprecated
@Getter
@Service
public class ScheduleVcsService extends ScheduleServiceBase {
    private final String jobPrefix = "TerrakubeV2_Vcs_";
    private final Class<? extends Job> jobClass = ScheduleVcs.class;
    private final String jobType = "Vcs";
    private final String jobDataKey = "vcsId";
}
