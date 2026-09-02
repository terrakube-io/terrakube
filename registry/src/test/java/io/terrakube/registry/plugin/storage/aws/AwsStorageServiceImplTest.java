package io.terrakube.registry.plugin.storage.aws;

import io.terrakube.registry.plugin.storage.StorageUnavailableException;
import io.terrakube.registry.service.git.GitService;
import io.terrakube.registry.service.git.ModuleVersionDownload;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URL;
import java.util.Optional;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AwsStorageServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSearchModuleAndUploadIfNotExist() throws IOException {
        S3Client s3Client = mock(S3Client.class);
        GitService gitService = mock(GitService.class);
        String bucketName = "test-bucket";
        String registryHostname = "https://registry.terrakube.io";

        AwsStorageServiceImpl awsStorageService = AwsStorageServiceImpl.builder()
                .s3client(s3Client)
                .bucketName(bucketName)
                .gitService(gitService)
                .registryHostname(registryHostname)
                .build();

        // Mock headObject to throw 404
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());

        // Mock gitService
        File gitCloneDir = tempDir.resolve("git-clone").toFile();
        assertTrue(gitCloneDir.mkdirs());
        File dummyFile = new File(gitCloneDir, "main.tf");
        FileUtils.writeStringToFile(dummyFile, "resource \"null_resource\" \"this\" {}", StandardCharsets.UTF_8);

        when(gitService.getCloneRepositoryByTag(any(ModuleVersionDownload.class)))
                .thenReturn(gitCloneDir);

        ModuleVersionDownload download = new ModuleVersionDownload("source", "1.0.0", "v1.0.0", "vcsType",
                "vcsConn", "token", "tag", "folder");
        String result = awsStorageService.searchModule("org", "module", "aws", download);

        assertEquals("https://registry.terrakube.io/terraform/modules/v1/download/org/module/aws/1.0.0/module.zip", result);
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void shouldSearchModuleAndReturnUrlIfExist() {
        S3Client s3Client = mock(S3Client.class);
        GitService gitService = mock(GitService.class);
        String bucketName = "test-bucket";
        String registryHostname = "https://registry.terrakube.io";

        AwsStorageServiceImpl awsStorageService = AwsStorageServiceImpl.builder()
                .s3client(s3Client)
                .bucketName(bucketName)
                .gitService(gitService)
                .registryHostname(registryHostname)
                .build();

        // Mock headObject to succeed
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());

        ModuleVersionDownload download = new ModuleVersionDownload("source", "1.0.0", "v1.0.0", "vcsType",
                "vcsConn", "token", "tag", "folder");
        String result = awsStorageService.searchModule("org", "module", "aws", download);

        assertEquals("https://registry.terrakube.io/terraform/modules/v1/download/org/module/aws/1.0.0/module.zip", result);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verifyNoInteractions(gitService);
    }

    @Test
    void shouldDownloadModule() {
        S3Client s3Client = mock(S3Client.class);
        GitService gitService = mock(GitService.class);
        String bucketName = "test-bucket";
        String registryHostname = "https://registry.terrakube.io";

        AwsStorageServiceImpl awsStorageService = AwsStorageServiceImpl.builder()
                .s3client(s3Client)
                .bucketName(bucketName)
                .gitService(gitService)
                .registryHostname(registryHostname)
                .build();

        byte[] expectedData = "test-data".getBytes();
        ResponseBytes<GetObjectResponse> responseBytes = mock(ResponseBytes.class);
        when(responseBytes.asByteArray()).thenReturn(expectedData);

        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
                .thenReturn(responseBytes);

        byte[] result = awsStorageService.downloadModule("org", "module", "aws", "1.0.0");

        assertArrayEquals(expectedData, result);
    }

    @Test
    void shouldThrowStorageUnavailableWhenDownloadFails() {
        S3Client s3Client = mock(S3Client.class);
        GitService gitService = mock(GitService.class);

        AwsStorageServiceImpl awsStorageService = AwsStorageServiceImpl.builder()
                .s3client(s3Client)
                .bucketName("test")
                .gitService(gitService)
                .registryHostname("host")
                .build();

        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
                .thenThrow(new RuntimeException("S3 error"));

        assertThrows(StorageUnavailableException.class,
                () -> awsStorageService.downloadModule("org", "module", "aws", "1.0.0"));
    }

    @Test
    void shouldThrowStorageUnavailableWhenHeadObjectFailsWithNon404() {
        S3Client s3Client = mock(S3Client.class);
        GitService gitService = mock(GitService.class);

        AwsStorageServiceImpl awsStorageService = AwsStorageServiceImpl.builder()
                .s3client(s3Client)
                .bucketName("test-bucket")
                .gitService(gitService)
                .registryHostname("https://registry.terrakube.io")
                .build();

        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(500).message("throttled").build());

        ModuleVersionDownload download = new ModuleVersionDownload("source", "1.0.0", "v1.0.0", "vcsType",
                "vcsConn", "token", "tag", "folder");

        assertThrows(StorageUnavailableException.class,
                () -> awsStorageService.searchModule("org", "module", "aws", download));
        verifyNoInteractions(gitService);
    }

    @Test
    void shouldReturnEmptyWhenPresignedRedirectDisabled() {
        S3Client s3Client = mock(S3Client.class);
        GitService gitService = mock(GitService.class);

        AwsStorageServiceImpl awsStorageService = AwsStorageServiceImpl.builder()
                .s3client(s3Client)
                .bucketName("test-bucket")
                .gitService(gitService)
                .registryHostname("host")
                .presignedRedirectEnabled(false)
                .build();

        Optional<java.net.URI> result = awsStorageService.getPresignedDownloadUrl("org", "module", "aws", "1.0.0");

        assertTrue(result.isEmpty());
        verifyNoInteractions(s3Client);
    }

    @Test
    void shouldReturnPresignedUrlWhenRedirectEnabled() throws Exception {
        S3Client s3Client = mock(S3Client.class);
        GitService gitService = mock(GitService.class);
        S3Presigner s3Presigner = mock(S3Presigner.class);
        PresignedGetObjectRequest presignedGetObjectRequest = mock(PresignedGetObjectRequest.class);

        when(presignedGetObjectRequest.url()).thenReturn(new URL("https://test-bucket.s3.amazonaws.com/registry/org/module/aws/1.0.0/module.zip?X-Amz-Signature=redacted"));
        when(s3Presigner.presignGetObject(any(software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest.class)))
                .thenReturn(presignedGetObjectRequest);

        AwsStorageServiceImpl awsStorageService = AwsStorageServiceImpl.builder()
                .s3client(s3Client)
                .bucketName("test-bucket")
                .gitService(gitService)
                .registryHostname("host")
                .s3Presigner(s3Presigner)
                .presignedRedirectEnabled(true)
                .presignedUrlExpirySeconds(300)
                .build();

        Optional<java.net.URI> result = awsStorageService.getPresignedDownloadUrl("org", "module", "aws", "1.0.0");

        assertTrue(result.isPresent());
        assertEquals("https", result.get().getScheme());
        verifyNoInteractions(s3Client);
    }

    @Test
    void shouldThrowStorageUnavailableWhenPresigningFails() {
        S3Client s3Client = mock(S3Client.class);
        GitService gitService = mock(GitService.class);
        S3Presigner s3Presigner = mock(S3Presigner.class);

        when(s3Presigner.presignGetObject(any(software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest.class)))
                .thenThrow(new RuntimeException("presign failure"));

        AwsStorageServiceImpl awsStorageService = AwsStorageServiceImpl.builder()
                .s3client(s3Client)
                .bucketName("test-bucket")
                .gitService(gitService)
                .registryHostname("host")
                .s3Presigner(s3Presigner)
                .presignedRedirectEnabled(true)
                .build();

        assertThrows(StorageUnavailableException.class,
                () -> awsStorageService.getPresignedDownloadUrl("org", "module", "aws", "1.0.0"));
    }
}
