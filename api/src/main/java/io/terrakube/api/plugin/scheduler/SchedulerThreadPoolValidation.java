package io.terrakube.api.plugin.scheduler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.terrakube.api.plugin.datasource.DataSourceConfigurationProperties;

import lombok.extern.slf4j.Slf4j;

// Every Quartz worker thread that picks up a job may open its own DB connection (Job/Step/
// Workspace reads and writes inside ScheduleJob.execute) - if the Quartz thread pool is sized at
// or above the Hikari pool, a burst of jobs firing at once can starve every other consumer of the
// same pool (webhook ingestion, the workspace-fanout executor, plain API requests) of a connection
// entirely. Throwing from the constructor fails Spring context startup, catching a
// misconfiguration before it becomes a production outage instead of after.
@Slf4j
@Component
public class SchedulerThreadPoolValidation {

    public SchedulerThreadPoolValidation(
            @Value("${io.terrakube.scheduler.thread-count:8}") int schedulerThreadCount,
            DataSourceConfigurationProperties dataSourceConfigurationProperties) {
        int poolSize = dataSourceConfigurationProperties.getPoolSize();
        if (schedulerThreadCount >= poolSize) {
            throw new IllegalStateException(String.format(
                    "io.terrakube.scheduler.thread-count (%d) must be less than "
                            + "io.terrakube.api.plugin.datasource.poolSize (%d) - a Quartz thread pool at or "
                            + "above the DB connection pool size can starve every other consumer of that pool.",
                    schedulerThreadCount, poolSize));
        }
        log.info("Quartz thread pool size {} validated against DB connection pool size {}", schedulerThreadCount,
                poolSize);
    }
}
