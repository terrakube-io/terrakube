package io.terrakube.api.plugin.storage.configuration;

import io.terrakube.api.plugin.storage.aws.AwsStorageTypeProperties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    }

    @Test
    void s3ServiceConfigurationHonorsS3CompatibleOverrides() {
        AwsStorageTypeProperties props = new AwsStorageTypeProperties();
        props.setEndpointRegion("us-east-1");
        props.setChunkedEncodingEnabled(false);

        S3Configuration config = StorageTypeAutoConfiguration.s3ServiceConfiguration(props);

        assertEquals("us-east-1", props.getEndpointRegion());
        assertFalse(config.chunkedEncodingEnabled());
        assertTrue(config.pathStyleAccessEnabled());
    }

    @Test
    void s3ServiceConfigurationHonorsDisabledPathStyleAccess() {
        AwsStorageTypeProperties props = new AwsStorageTypeProperties();
        props.setPathStyleAccessEnabled(false);

        S3Configuration config = StorageTypeAutoConfiguration.s3ServiceConfiguration(props);

        assertFalse(config.pathStyleAccessEnabled());
    }

    @Test
    void s3ServiceConfigurationLeavesChecksumBehaviorToClient() {
        AwsStorageTypeProperties props = new AwsStorageTypeProperties();
        props.setChecksumValidationEnabled(false);

        S3Configuration config = StorageTypeAutoConfiguration.s3ServiceConfiguration(props);

        // The SDK refuses to build a client when checksum behavior is set on both S3Configuration
        // and the client (#3528), so it must stay unset here.
        assertNull(config.toBuilder().checksumValidationEnabled());
    }

    @Test
    void s3CompatibleClientBuildsWithChecksumValidationEnabled() {
        AwsStorageTypeProperties props = s3CompatibleProps();

        try (S3Client client = StorageTypeAutoConfiguration.s3CompatibleClient(props,
                StorageTypeAutoConfiguration.storageClientOverride(props))) {
            assertEquals(RequestChecksumCalculation.WHEN_REQUIRED,
                    client.serviceClientConfiguration().requestChecksumCalculation());
            assertEquals(ResponseChecksumValidation.WHEN_SUPPORTED,
                    client.serviceClientConfiguration().responseChecksumValidation());
        }
    }

    @Test
    void s3CompatibleClientBuildsWithChecksumValidationDisabled() {
        AwsStorageTypeProperties props = s3CompatibleProps();
        props.setChecksumValidationEnabled(false);

        try (S3Client client = StorageTypeAutoConfiguration.s3CompatibleClient(props,
                StorageTypeAutoConfiguration.storageClientOverride(props))) {
            assertEquals(RequestChecksumCalculation.WHEN_REQUIRED,
                    client.serviceClientConfiguration().requestChecksumCalculation());
            assertEquals(ResponseChecksumValidation.WHEN_REQUIRED,
                    client.serviceClientConfiguration().responseChecksumValidation());
        }
    }

    private static AwsStorageTypeProperties s3CompatibleProps() {
        AwsStorageTypeProperties props = new AwsStorageTypeProperties();
        props.setEndpoint("http://localhost:9000");
        props.setAccessKey("minio");
        props.setSecretKey("minio123");
        props.setChecksumValidationEnabled(true);
        return props;
    }
}
