package io.terrakube.registry.plugin.storage.aws;

import io.terrakube.registry.service.git.GitService;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

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

        when(gitService.getCloneRepositoryByTag(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(gitCloneDir);

        String result = awsStorageService.searchModule("org", "module", "aws", "1.0.0", 
                "source", "vcsType", "vcsConn", "token", "tag", "folder");

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

        String result = awsStorageService.searchModule("org", "module", "aws", "1.0.0", 
                "source", "vcsType", "vcsConn", "token", "tag", "folder");

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
    void shouldReturnEmptyBytesWhenDownloadFails() {
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

        byte[] result = awsStorageService.downloadModule("org", "module", "aws", "1.0.0");

        assertEquals(0, result.length);
    }
}
