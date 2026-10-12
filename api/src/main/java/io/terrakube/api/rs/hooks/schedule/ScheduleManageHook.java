package io.terrakube.api.rs.hooks.schedule;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.rs.workspace.schedule.Schedule;
import org.quartz.SchedulerException;

import java.text.ParseException;
import java.util.Optional;

@AllArgsConstructor
@Slf4j
public class ScheduleManageHook implements LifeCycleHook<Schedule> {

    private ScheduleJobService scheduleJobService;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation, LifeCycleHookBinding.TransactionPhase transactionPhase, Schedule schedule, RequestScope requestScope, Optional<ChangeSpec> optional) {
        log.info("TriggerCreateHook {} {}", schedule.getCron(), schedule.getId().toString());
        try {
            switch (operation) {
                case CREATE:
                    scheduleJobService.createJobTrigger(schedule.getCron(), schedule.getId().toString());
                    break;
                case UPDATE:
                    // The Quartz trigger holds its own copy of the cron expression, so it is
                    // rebuilt on every update: a changed cron would otherwise keep firing on the
                    // old one, and a re-enabled schedule would never fire again.
                    scheduleJobService.deleteJobTrigger(schedule.getId().toString());
                    if (schedule.isEnabled()) {
                        scheduleJobService.createJobTrigger(schedule.getCron(), schedule.getId().toString());
                    }
                    break;
                case DELETE:
                    scheduleJobService.deleteJobTrigger(schedule.getId().toString());
                    break;
                default:
                    log.info("Not supported");
                    break;
            }

        } catch (ParseException | SchedulerException e) {
            log.error(e.getMessage());
        }
    }
}
