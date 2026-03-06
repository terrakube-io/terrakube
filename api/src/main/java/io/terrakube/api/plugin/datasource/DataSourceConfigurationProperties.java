package io.terrakube.api.plugin.datasource;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;

@Getter
@Setter
@PropertySource(value = "classpath:application.properties", ignoreResourceNotFound = true)
@PropertySource(value = "classpath:application-${spring.profiles.active}.properties", ignoreResourceNotFound = true)
@ConfigurationProperties(prefix = "io.terrakube.api.plugin.datasource")
public class DataSourceConfigurationProperties {
    private DataSourceType type;
    private String hostname;
    private String databaseName;
    private String databaseUser;
    private String databasePassword;
    private String sslMode;
    private String databasePort;
    private String databaseSchema;
    private boolean awsIamAuth;
    private boolean trustCertificate;
    private String awsRegion;

    private int poolSize = 10;
    private int poolMinIdle = 5;
    private long poolConnectionTimeout = 30000;
    private long poolIdleTimeout = 600000;
    private long poolMaxLifetime = 1800000;
}