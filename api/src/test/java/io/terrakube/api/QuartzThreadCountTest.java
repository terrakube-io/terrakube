package io.terrakube.api;

import io.terrakube.api.plugin.datasource.DataSourceConfigurationProperties;
import io.terrakube.api.plugin.scheduler.configuration.QuartzAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class QuartzThreadCountTest extends ServerApplicationTests {

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    DataSource dataSource;

    @Autowired
    DataSourceConfigurationProperties dataSourceConfigurationProperties;

    @Test
    void schedulerUsesTheConfiguredThreadCountByDefault() throws Exception {
        assertThat(scheduler.getMetaData().getThreadPoolSize()).isEqualTo(8);
    }

    @Test
    void schedulerFollowsAnOverriddenThreadCount() {
        SchedulerFactoryBean schedulerFactoryBean = new QuartzAutoConfiguration().schedulerFactoryBean(
                applicationContext, dataSource, dataSourceConfigurationProperties, "threadCountTest", 20);

        Properties quartzProperties = (Properties) ReflectionTestUtils.getField(schedulerFactoryBean, "quartzProperties");
        assertThat(quartzProperties.getProperty("org.quartz.threadPool.threadCount")).isEqualTo("20");
    }
}
