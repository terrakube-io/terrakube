package io.terrakube.executor.plugin.tfstate.configuration;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.plugin.tfstate.StorageRetryMetrics;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.plugin.tfstate.TerraformStateMetadataService;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.plugin.tfstate.aws.AwsTerraformStateImpl;
import io.terrakube.executor.plugin.tfstate.aws.AwsTerraformStateProperties;
import io.terrakube.executor.plugin.tfstate.azure.AzureTerraformStateProperties;
import io.terrakube.executor.plugin.tfstate.gcp.GcpTerraformStateProperties;
import io.terrakube.executor.plugin.tfstate.http.HttpTerraformStateImpl;
import io.terrakube.executor.plugin.tfstate.local.LocalTerraformStateImpl;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

class TerraformStateAutoConfigurationTest {

    @Test
    void responseChecksumValidationMapsFlagToSdkMode() {
        assertEquals(ResponseChecksumValidation.WHEN_SUPPORTED,
                TerraformStateAutoConfiguration.responseChecksumValidation(true));
        assertEquals(ResponseChecksumValidation.WHEN_REQUIRED,
                TerraformStateAutoConfiguration.responseChecksumValidation(false));
    }

    @Test
    void terraformStateSelectsHttpTerraformStateImplDirectly() {
        TerraformStateAutoConfiguration config = new TerraformStateAutoConfiguration();
        TerraformStateProperties props = new TerraformStateProperties();
        props.setType(TerraformStateType.HttpTerraformStateImpl);
        props.setHttpBackend(false);

        TerraformState state = config.terraformState(
                mock(TerrakubeClient.class),
                props,
                new AzureTerraformStateProperties(),
                new AwsTerraformStateProperties(),
                new GcpTerraformStateProperties(),
                new TerraformStatePathService(),
                new TerraformOutputPathService(),
                mock(TerraformStateMetadataService.class),
                mock(StorageRetryMetrics.class),
                mock(WorkspaceSecurity.class)
        );

        HttpTerraformStateImpl httpState = assertInstanceOf(HttpTerraformStateImpl.class, state);
        assertInstanceOf(LocalTerraformStateImpl.class, httpState.getDelegate());
    }

    @Test
    void terraformStateWrapsAwsBackendWithHttpTerraformStateImplWhenHttpBackendEnabled() {
        TerraformStateAutoConfiguration config = new TerraformStateAutoConfiguration();
        TerraformStateProperties props = new TerraformStateProperties();
        props.setType(TerraformStateType.AwsTerraformStateImpl);
        props.setHttpBackend(true);

        AwsTerraformStateProperties awsProps = new AwsTerraformStateProperties();
        awsProps.setAccessKey("test-access-key");
        awsProps.setSecretKey("test-secret-key");
        awsProps.setRegion("us-east-1");
        awsProps.setBucketName("test-bucket");
        awsProps.setEndpoint("");

        TerraformState state = config.terraformState(
                mock(TerrakubeClient.class),
                props,
                new AzureTerraformStateProperties(),
                awsProps,
                new GcpTerraformStateProperties(),
                new TerraformStatePathService(),
                new TerraformOutputPathService(),
                mock(TerraformStateMetadataService.class),
                mock(StorageRetryMetrics.class),
                mock(WorkspaceSecurity.class)
        );

        HttpTerraformStateImpl httpState = assertInstanceOf(HttpTerraformStateImpl.class, state);
        assertInstanceOf(AwsTerraformStateImpl.class, httpState.getDelegate());
    }
}
