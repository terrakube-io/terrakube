package io.terrakube.api.plugin.scheduler.spool;

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
@ConfigurationProperties(prefix = "io.terrakube.api.scheduler.spool-recovery")
public class SpoolRecoveryProperties {

    /**
     * Master switch for the spool recovery sweeper.
     */
    private boolean enabled = true;

    /**
     * Cron expression for the spool recovery Quartz trigger.
     * Defaults to every 30 seconds.
     */
    private String cron = "*/30 * * ? * *";

    /**
     * Max number of steps to claim and process per sweep batch.
     */
    private int batchSize = 50;

    /**
     * Maximum hours to retain spooled files before marking them EXPIRED and purging.
     */
    private long retentionHours = 24;

    /**
     * Duration in seconds for which an atomic lease is held by a worker instance.
     */
    private long leaseDurationSeconds = 120;

    /**
     * Maximum allowed size for a compressed spool object in bytes (default: 5 MB).
     */
    private int maxCompressedBytes = 5 * 1024 * 1024;

    /**
     * Maximum allowed size for an uncompressed spool payload in bytes (default: 15 MB).
     * Prevents decompression bomb denial-of-service (CWE-409).
     */
    private int maxUncompressedBytes = 15 * 1024 * 1024;
}
