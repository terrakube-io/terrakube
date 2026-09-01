package io.terrakube.executor.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@PropertySource(value = "classpath:application.properties", ignoreResourceNotFound = true)
@PropertySource(value = "classpath:application-${spring.profiles.active}.properties", ignoreResourceNotFound = true)
@ConfigurationProperties(prefix = "io.terrakube.executor.flags")
public class ExecutorFlagsProperties {

    private boolean ephemeral;
    private String ephemeralJobData;
    private String batchJobFile;
    private boolean disableAcknowledge;

    // When true, structured plan/apply context persistence is handed to a bounded async queue
    // instead of running synchronously on the Terraform process-output reader thread. Set false to
    // fall back to the previous synchronous behaviour.
    private boolean asyncStructuredOutput = true;

}
