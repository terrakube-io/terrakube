package io.terrakube.registry.plugin.storage.aws;

import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.service.git.GitService;
import org.zeroturnaround.zip.ZipUtil;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.File;
import java.io.IOException;

@Slf4j
@Builder
public class AwsStorageServiceImpl implements StorageService {

    private static String BUCKET_ZIP_MODULE_LOCATION = "registry/%s/%s/%s/%s/module.zip";
    private static String BUCKET_DOWNLOAD_MODULE_LOCATION = "%s/terraform/modules/v1/download/%s/%s/%s/%s/module.zip";
    private static final String S3_ERROR_LOG = "S3 Not found: {}";

    @NonNull
    private S3Client s3client;

    @NonNull
    private String bucketName;

    @NonNull
    GitService gitService;

    @NonNull
    String registryHostname;

    @Override
    public String searchModule(String organizationName, String moduleName, String providerName, String moduleVersion,
            String source, String vcsType, String vcsConnectionType, String accessToken, String tagPrefix,
            String folder) {
        String blobKey = String.format(BUCKET_ZIP_MODULE_LOCATION, organizationName, moduleName, providerName,
                moduleVersion);
        log.info("Checking Aws S3 Object exist {}", blobKey);

        if (!doesObjectExistByListObjects(bucketName, blobKey)) {
            File gitCloneDirectory = gitService.getCloneRepositoryByTag(source, moduleVersion, vcsType,
                    vcsConnectionType, accessToken, tagPrefix, folder);
            File moduleZip = new File(gitCloneDirectory.getAbsolutePath() + ".zip");
            ZipUtil.pack(gitCloneDirectory, moduleZip);

            log.info("Uploading Aws S3 Object {}", blobKey);
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(blobKey)
                    .contentType("application/zip")
                    .build();

            log.info("Running PUT Aws S3 Object {}", blobKey);
            log.info("Path {}", moduleZip.getAbsolutePath());
            s3client.putObject(putObjectRequest, RequestBody.fromFile(moduleZip));

            log.info("Upload Aws S3 Object completed {}", blobKey);
            try {
                FileUtils.cleanDirectory(gitCloneDirectory);
                if (FileUtils.deleteQuietly(moduleZip))
                    log.info("Successfully delete folder");
            } catch (IOException e) {
                log.error(e.getMessage());
            }
        }

        return String.format(BUCKET_DOWNLOAD_MODULE_LOCATION, registryHostname, organizationName, moduleName,
                providerName, moduleVersion);
    }

    public boolean doesObjectExistByListObjects(String bucketName, String key) {
        try {
            HeadObjectRequest headObjectRequest = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            s3client.headObject(headObjectRequest);

            log.info("Object exists: {}", key);
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                log.info("Object does not exist");
                return false;
            } else {
                throw e;
            }
        }
    }

    @Override
    public byte[] downloadModule(String organizationName, String moduleName, String providerName,
            String moduleVersion) {
        byte[] data;
        try {
            log.info("Searching: /registry/{}/{}/{}/{}/module.zip", organizationName, moduleName, providerName,
                    moduleVersion);
            GetObjectRequest objectRequest = GetObjectRequest.builder()
                    .key(String.format(BUCKET_ZIP_MODULE_LOCATION,
                            organizationName, moduleName, providerName, moduleVersion))
                    .bucket(bucketName)
                    .build();
            ResponseBytes<GetObjectResponse> objectBytes = s3client.getObject(objectRequest,
                    ResponseTransformer.toBytes());
            data = objectBytes.asByteArray();
        } catch (Exception e) {
            log.error(S3_ERROR_LOG, e.getMessage());
            data = new byte[0];
        }
        return data;
    }

}
