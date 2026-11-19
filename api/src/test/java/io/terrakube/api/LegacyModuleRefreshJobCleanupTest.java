package io.terrakube.api;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;

import io.terrakube.api.plugin.scheduler.module.DeleteStorageCacheJob;
import io.terrakube.api.plugin.scheduler.module.LegacyModuleRefreshJobCleanup;
import io.terrakube.api.plugin.scheduler.module.ModuleRefreshJob;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyModuleRefreshJobCleanupTest extends ServerApplicationTests {

    @Autowired
    LegacyModuleRefreshJobCleanup cleanup;

    private JobKey store(Class<? extends org.quartz.Job> jobClass, String name) throws SchedulerException {
        // Stored without a trigger, so neither job runs while the test looks at them.
        JobDetail jobDetail = JobBuilder.newJob(jobClass).withIdentity(name).storeDurably().build();
        scheduler.addJob(jobDetail, false);
        return jobDetail.getKey();
    }

    @Test
    @SuppressWarnings("deprecation")
    void deletesOnlyTheLegacyRefreshJobs() throws SchedulerException {
        JobKey legacyRefresh = store(ModuleRefreshJob.class, "TerrakubeV2_ModuleRefresh_" + UUID.randomUUID());
        // Module storage cleanup used to be stored under the refresh prefix too.
        JobKey storageCleanup = store(DeleteStorageCacheJob.class, "TerrakubeV2_ModuleRefresh__" + UUID.randomUUID());

        cleanup.deleteLegacyJobs();

        assertThat(scheduler.checkExists(legacyRefresh)).isFalse();
        assertThat(scheduler.checkExists(storageCleanup)).isTrue();
        scheduler.deleteJob(storageCleanup);
    }
}
