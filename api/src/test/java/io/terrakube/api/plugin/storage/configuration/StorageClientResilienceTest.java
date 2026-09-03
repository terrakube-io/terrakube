package io.terrakube.api.plugin.storage.configuration;

import io.terrakube.api.plugin.storage.aws.AwsStorageTypeProperties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageClientResilienceTest {

    @Test
    void buildsOverrideConfigFromProperties() {
        AwsStorageTypeProperties props = new AwsStorageTypeProperties();
        props.setApiCallTimeoutSeconds(10);
        props.setApiCallAttemptTimeoutSeconds(3);
        props.setMaxRetryAttempts(2);

        ClientOverrideConfiguration config = StorageTypeAutoConfiguration.storageClientOverride(props);

        assertEquals(Duration.ofSeconds(10), config.apiCallTimeout().orElseThrow());
        assertEquals(Duration.ofSeconds(3), config.apiCallAttemptTimeout().orElseThrow());
        RetryPolicy retryPolicy = config.retryPolicy().orElseThrow();
        assertEquals(2, retryPolicy.numRetries().intValue());
    }

    @Test
    void defaultsAreConservativeWhenPropertiesUnset() {
        AwsStorageTypeProperties props = new AwsStorageTypeProperties();

        assertEquals(10, props.getApiCallTimeoutSeconds());
        assertEquals(3, props.getApiCallAttemptTimeoutSeconds());
        assertEquals(2, props.getMaxRetryAttempts());
    }

    @Test
    void s3ServiceConfigurationDefaultsPreserveCurrentBehavior() {
        AwsStorageTypeProperties props = new AwsStorageTypeProperties();

        S3Configuration config = StorageTypeAutoConfiguration.s3ServiceConfiguration(props);

        assertEquals("auto", props.getEndpointRegion());
        assertTrue(config.pathStyleAccessEnabled());
        assertTrue(config.chunkedEncodingEnabled());
        assertTrue(config.checksumValidationEnabled());
    }

    @Test
    void s3ServiceConfigurationHonorsS3CompatibleOverrides() {
        AwsStorageTypeProperties props = new AwsStorageTypeProperties();
        props.setEndpointRegion("us-east-1");
        props.setChunkedEncodingEnabled(false);
        props.setChecksumValidationEnabled(false);

        S3Configuration config = StorageTypeAutoConfiguration.s3ServiceConfiguration(props);

        assertEquals("us-east-1", props.getEndpointRegion());
        assertFalse(config.chunkedEncodingEnabled());
        assertFalse(config.checksumValidationEnabled());
        assertTrue(config.pathStyleAccessEnabled());
    }
}
