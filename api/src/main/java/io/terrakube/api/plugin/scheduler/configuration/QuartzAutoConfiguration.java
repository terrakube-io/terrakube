package io.terrakube.api.plugin.scheduler.configuration;

import io.terrakube.api.plugin.datasource.DataSourceConfigurationProperties;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;
import org.springframework.util.Assert;

import javax.sql.DataSource;
import java.util.Properties;

@Configuration
public class QuartzAutoConfiguration {

    class AutowireCapableBeanJobFactory extends SpringBeanJobFactory {

        private final AutowireCapableBeanFactory beanFactory;

        @Autowired
        public AutowireCapableBeanJobFactory(AutowireCapableBeanFactory beanFactory) {
            Assert.notNull(beanFactory, "Bean factory must not be null");
            this.beanFactory = beanFactory;
        }

        @Override
        protected Object createJobInstance(TriggerFiredBundle bundle) throws Exception {
            Object jobInstance = super.createJobInstance(bundle);
            this.beanFactory.autowireBean(jobInstance);
            this.beanFactory.initializeBean(jobInstance, "jobInstance");
            return jobInstance;
        }
    }

    @Bean
    public SchedulerFactoryBean schedulerFactoryBean(ApplicationContext applicationContext, DataSource dataSource, DataSourceConfigurationProperties dataSourceConfigurationProperties) {
        SchedulerFactoryBean schedulerFactoryBean = new SchedulerFactoryBean();
        schedulerFactoryBean.setJobFactory(new AutowireCapableBeanJobFactory(applicationContext.getAutowireCapableBeanFactory()));
        schedulerFactoryBean.setDataSource(dataSource);
        Properties properties = new Properties();
        properties.put("org.quartz.jobStore.class","org.springframework.scheduling.quartz.LocalDataSourceJobStore");
        properties.put("org.quartz.jobStore.isClustered","true");
        properties.put("org.quartz.scheduler.instanceId","AUTO");
        properties.put("org.quartz.threadPool.threadCount","20");
        switch(dataSourceConfigurationProperties.getType()){
            case SQL_AZURE:
                properties.put("org.quartz.jobStore.driverDelegateClass","org.quartz.impl.jdbcjobstore.MSSQLDelegate");
                break;
            case POSTGRESQL:
                properties.put("org.quartz.jobStore.driverDelegateClass","org.quartz.impl.jdbcjobstore.PostgreSQLDelegate");
                break;
            default:
                properties.put("org.quartz.jobStore.driverDelegateClass","org.quartz.impl.jdbcjobstore.StdJDBCDelegate");
                break;
        }
        schedulerFactoryBean.setQuartzProperties(properties);
        return schedulerFactoryBean;
    }

    @Bean
    public Scheduler scheduler(SchedulerFactoryBean schedulerFactoryBean) throws SchedulerException {
        Scheduler scheduler = schedulerFactoryBean.getScheduler();

        scheduler.start();
        return scheduler;
    }

}
