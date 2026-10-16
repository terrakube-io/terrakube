package io.terrakube.api.plugin.storage;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import io.terrakube.api.plugin.storage.aws.AwsStorageTypeServiceImpl;
import io.terrakube.api.plugin.storage.azure.AzureStorageTypeServiceImpl;
import io.terrakube.api.plugin.storage.gcp.GcpStorageTypeServiceImpl;
import io.terrakube.api.plugin.storage.local.LocalStorageTypeServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class HardenedStorageWritesTest {

    @TempDir
    Path home;
    String previousHome;

    @BeforeEach
    void setHome() {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", previousHome);
    }

    @Test
    void awsWritesTheSameKeysTheExecutorBackendUsed() {
        S3Client s3 = mock(S3Client.class);
        StorageTypeService storage = AwsStorageTypeServiceImpl.builder().s3client(s3).bucketName("bucket").build();
        storage.uploadCurrentState("o", "w", new byte[]{1});
        storage.uploadTerraformPlan("o", "w", "1", "s", new byte[]{2});
        storage.uploadStepOutput("o", "1", "s", new byte[]{3});

        ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3, times(3)).putObject(requests.capture(), any(RequestBody.class));
        assertThat(requests.getAllValues()).extracting(PutObjectRequest::key).containsExactly(
                "tfstate/o/w/terraform.tfstate",
                "tfstate/o/w/1/s/terraformLibrary.tfPlan",
                "tfoutput/o/1/s.tfoutput");
        assertThat(requests.getAllValues()).allMatch(r -> r.bucket().equals("bucket"));
    }

    @Test
    void awsFailuresPropagate() {
        S3Client s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(new IllegalStateException("denied"));
        StorageTypeService storage = AwsStorageTypeServiceImpl.builder().s3client(s3).bucketName("bucket").build();
        assertThatThrownBy(() -> storage.uploadCurrentState("o", "w", new byte[]{1})).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void gcpWritesTheGcsBackendLayout() {
        Storage gcs = mock(Storage.class);
        StorageTypeService storage = GcpStorageTypeServiceImpl.builder().storage(gcs).bucketName("bucket").build();
        storage.uploadCurrentState("o", "w", new byte[]{1});
        storage.uploadTerraformPlan("o", "w", "1", "s", new byte[]{2});
        storage.uploadStepOutput("o", "1", "s", new byte[]{3});

        ArgumentCaptor<BlobInfo> blobs = ArgumentCaptor.forClass(BlobInfo.class);
        verify(gcs, times(3)).create(blobs.capture(), any(byte[].class));
        assertThat(blobs.getAllValues()).extracting(BlobInfo::getName).containsExactly(
                "tfstate/o/w/terraform.tfstate/default.tfstate",
                "tfstate/o/w/1/s/terraformLibrary.tfPlan",
                "tfoutput/o/1/s.tfoutput");
    }

    @Test
    void azureWritesTheAzurermBackendLayoutAndCreatesMissingContainers() {
        BlobServiceClient service = mock(BlobServiceClient.class);
        BlobContainerClient container = mock(BlobContainerClient.class);
        BlobClient blob = mock(BlobClient.class);
        when(service.getBlobContainerClient(anyString())).thenReturn(container);
        when(container.exists()).thenReturn(false, true, true);
        when(container.getBlobClient(anyString())).thenReturn(blob);
        StorageTypeService storage = AzureStorageTypeServiceImpl.builder().blobServiceClient(service).build();

        storage.uploadCurrentState("o", "w", new byte[]{1});
        storage.uploadTerraformPlan("o", "w", "1", "s", new byte[]{2});
        storage.uploadStepOutput("o", "1", "s", new byte[]{3});

        verify(container, times(1)).create();
        verify(service, times(2)).getBlobContainerClient("tfstate");
        verify(service).getBlobContainerClient("tfoutput");
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        verify(container, times(3)).getBlobClient(names.capture());
        assertThat(names.getAllValues()).containsExactly("o/w/terraform.tfstate", "o/w/1/s/terraformLibrary.tfPlan", "o/1/s.tfoutput");
        verify(blob, times(3)).upload(any(BinaryData.class), eq(true));
    }

    @Test
    void localWritesAreReadBackByTheExistingReaders() throws Exception {
        StorageTypeService storage = LocalStorageTypeServiceImpl.builder().build();
        storage.uploadCurrentState("o", "w", "state".getBytes());
        storage.uploadTerraformPlan("o", "w", "1", "s", "plan".getBytes());
        storage.uploadStepOutput("o", "1", "s", "output".getBytes());

        assertThat(new String(storage.getCurrentTerraformState("o", "w"))).isEqualTo("state");
        assertThat(new String(storage.getTerraformPlan("o", "w", "1", "s"))).isEqualTo("plan");
        assertThat(new String(storage.getStepOutput("o", "1", "s"))).isEqualTo("output");
    }

    @Test
    void localFailuresPropagate() throws Exception {
        Files.writeString(home.resolve(".terraform-spring-boot"), "not a directory");
        StorageTypeService storage = LocalStorageTypeServiceImpl.builder().build();
        assertThatThrownBy(() -> storage.uploadCurrentState("o", "w", List.of().toString().getBytes()))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void awsDistinguishesMissingStateFromErrors() {
        S3Client s3 = mock(S3Client.class);
        StorageTypeService storage = AwsStorageTypeServiceImpl.builder().s3client(s3).bucketName("bucket").build();
        when(s3.getObject(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class), any(software.amazon.awssdk.core.sync.ResponseTransformer.class)))
                .thenThrow(software.amazon.awssdk.services.s3.model.NoSuchKeyException.builder().build());
        assertThat(storage.readCurrentState("o", "w")).isEmpty();
        reset(s3);
        when(s3.getObject(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class), any(software.amazon.awssdk.core.sync.ResponseTransformer.class)))
                .thenThrow(software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(500).build());
        assertThatThrownBy(() -> storage.readCurrentState("o", "w")).isInstanceOf(software.amazon.awssdk.services.s3.model.S3Exception.class);
        reset(s3);
        when(s3.getObject(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class), any(software.amazon.awssdk.core.sync.ResponseTransformer.class)))
                .thenReturn(software.amazon.awssdk.core.ResponseBytes.fromByteArray(
                        software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().build(), "s".getBytes()));
        assertThat(storage.readCurrentState("o", "w")).contains("s".getBytes());

        storage.uploadStateHistory("o", "w", "h", "j".getBytes(), "r".getBytes());
        ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3, times(2)).putObject(requests.capture(), any(RequestBody.class));
        assertThat(requests.getAllValues()).extracting(PutObjectRequest::key)
                .containsExactly("tfstate/o/w/state/h.json", "tfstate/o/w/state/h.raw.json");
    }

    @Test
    void gcpAndAzureDistinguishMissingStateFromErrors() {
        Storage gcs = mock(Storage.class);
        StorageTypeService gcp = GcpStorageTypeServiceImpl.builder().storage(gcs).bucketName("bucket").build();
        assertThat(gcp.readCurrentState("o", "w")).isEmpty();
        com.google.cloud.storage.Blob blob = mock(com.google.cloud.storage.Blob.class);
        when(blob.getContent()).thenReturn("s".getBytes());
        when(gcs.get(any(com.google.cloud.storage.BlobId.class))).thenReturn(blob);
        assertThat(gcp.readCurrentState("o", "w")).contains("s".getBytes());
        gcp.uploadStateHistory("o", "w", "h", "j".getBytes(), "r".getBytes());
        ArgumentCaptor<BlobInfo> blobs = ArgumentCaptor.forClass(BlobInfo.class);
        verify(gcs, times(2)).create(blobs.capture(), any(byte[].class));
        assertThat(blobs.getAllValues()).extracting(BlobInfo::getName).containsExactly("tfstate/o/w/state/h.json", "tfstate/o/w/state/h.raw.json");

        BlobServiceClient service = mock(BlobServiceClient.class);
        BlobContainerClient container = mock(BlobContainerClient.class);
        BlobClient azureBlob = mock(BlobClient.class);
        when(service.getBlobContainerClient(anyString())).thenReturn(container);
        when(container.exists()).thenReturn(true);
        when(container.getBlobClient(anyString())).thenReturn(azureBlob);
        StorageTypeService azure = AzureStorageTypeServiceImpl.builder().blobServiceClient(service).build();
        com.azure.storage.blob.models.BlobStorageException notFound = mock(com.azure.storage.blob.models.BlobStorageException.class);
        when(notFound.getStatusCode()).thenReturn(404);
        when(azureBlob.downloadContent()).thenThrow(notFound);
        assertThat(azure.readCurrentState("o", "w")).isEmpty();
        com.azure.storage.blob.models.BlobStorageException failure = mock(com.azure.storage.blob.models.BlobStorageException.class);
        when(failure.getStatusCode()).thenReturn(503);
        reset(azureBlob);
        when(azureBlob.downloadContent()).thenThrow(failure);
        assertThatThrownBy(() -> azure.readCurrentState("o", "w")).isSameAs(failure);
        reset(azureBlob);
        when(azureBlob.downloadContent()).thenReturn(BinaryData.fromBytes("s".getBytes()));
        assertThat(azure.readCurrentState("o", "w")).contains("s".getBytes());
        azure.uploadStateHistory("o", "w", "h", "j".getBytes(), "r".getBytes());
        verify(container).getBlobClient("o/w/state/h.json");
        verify(container).getBlobClient("o/w/state/h.raw.json");
    }

    @Test
    void localHistoryWritesLeaveTheCurrentStateAlone() throws Exception {
        StorageTypeService storage = LocalStorageTypeServiceImpl.builder().build();
        assertThat(storage.readCurrentState("o", "w")).isEmpty();
        storage.uploadCurrentState("o", "w", "current".getBytes());
        storage.uploadStateHistory("o", "w", "h", "json".getBytes(), "raw".getBytes());
        assertThat(storage.readCurrentState("o", "w")).contains("current".getBytes());
        assertThat(new String(storage.getTerraformStateJson("o", "w", "h"))).isEqualTo("json");
        Files.delete(home.resolve(".terraform-spring-boot/local/backend/o/w/terraform.tfstate"));
        Files.createDirectory(home.resolve(".terraform-spring-boot/local/backend/o/w/terraform.tfstate"));
        assertThatThrownBy(() -> storage.readCurrentState("o", "w")).isInstanceOf(UncheckedIOException.class);
    }
}
