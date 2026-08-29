package io.terrakube.registry.plugin.storage.aws;

import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import io.terrakube.registry.plugin.storage.StorageService;
import io.terrakube.registry.plugin.storage.StorageUnavailableException;
import io.terrakube.registry.service.git.GitService;
import io.terrakube.registry.service.git.ModuleVersionDownload;
import org.zeroturnaround.zip.ZipUtil;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

@Slf4j
@Builder
public class AwsStorageServiceImpl implements StorageService {

    private static final String BUCKET_ZIP_MODULE_LOCATION = "registry/%s/%s/%s/%s/module.zip";
    private static final String BUCKET_DOWNLOAD_MODULE_LOCATION = "%s/terraform/modules/v1/download/%s/%s/%s/%s/module.zip";
    private static final String S3_ERROR_LOG = "S3 operation failed for key {}: {}";

    @NonNull
    private S3Client s3client;

    @NonNull
    private String bucketName;

    @NonNull
    GitService gitService;

    @NonNull
    String registryHostname;

    // Not @NonNull: only required when presignedRedirectEnabled is true (AWS storage with the
    // redirect flag on). Non-AWS test setups and rollback (flag off) never touch it.
    private S3Presigner s3Presigner;

    @Builder.Default
    private int presignedUrlExpirySeconds = 300;

    @Builder.Default
    private boolean presignedRedirectEnabled = false;

    @Override
    public String searchModule(String organizationName, String moduleName, String providerName,
            ModuleVersionDownload download) {
        String moduleVersion = download.version();
        String blobKey = String.format(BUCKET_ZIP_MODULE_LOCATION, organizationName, moduleName, providerName,
                moduleVersion);
        log.info("Checking Aws S3 Object exist {}", blobKey);

        try {
            if (!doesObjectExistByListObjects(bucketName, blobKey)) {
                File gitCloneDirectory = gitService.getCloneRepositoryByTag(download);
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
        } catch (SdkException e) {
            // Covers HeadObject/PutObject connectivity failures, timeouts, and throttling - a
            // cold cache miss must fail loudly rather than silently produce a bad/missing path
            // that a later ZIP request can't recover from.
            log.error(S3_ERROR_LOG, blobKey, e.getMessage());
            throw new StorageUnavailableException("S3 operation failed while resolving module path for key " + blobKey, e);
        }

        return String.format(BUCKET_DOWNLOAD_MODULE_LOCATION, registryHostname, organizationName, moduleName,
                providerName, moduleVersion);
    }

    /**
     * Presigned URL is intentionally not cached alongside the module path (see
     * getModuleVersionPath's 10-minute cache) - its validity window is much shorter than the
     * cache TTL, so every ZIP request gets a fresh signature computed here.
     */
    @Override
    public Optional<URI> getPresignedDownloadUrl(String organizationName, String moduleName, String providerName,
            String moduleVersion) {
        if (!presignedRedirectEnabled || s3Presigner == null) {
            return Optional.empty();
        }

        String blobKey = String.format(BUCKET_ZIP_MODULE_LOCATION, organizationName, moduleName, providerName,
                moduleVersion);
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(blobKey)
                    .build();
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(presignedUrlExpirySeconds))
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
            // Never log presignedRequest.url() itself - it carries the signed query string.
            log.info("Generated presigned download URL for bucket {} key {}", bucketName, blobKey);
            return Optional.of(presignedRequest.url().toURI());
        } catch (Exception e) {
            log.error("Failed to generate presigned URL for bucket {} key {}: {}", bucketName, blobKey, e.getMessage());
            throw new StorageUnavailableException("Failed to generate presigned URL for key " + blobKey, e);
        }
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

    /**
     * Legacy byte-proxy path, retained only for the presigned-redirect rollback/compatibility
     * case (presignedRedirectEnabled=false). An S3 failure here must surface as an error, never
     * as an empty ZIP that Terraform would try to unpack.
     */
    @Override
    public byte[] downloadModule(String organizationName, String moduleName, String providerName,
            String moduleVersion) {
        String blobKey = String.format(BUCKET_ZIP_MODULE_LOCATION, organizationName, moduleName, providerName,
                moduleVersion);
        try {
            log.info("Searching: {}", blobKey);
            GetObjectRequest objectRequest = GetObjectRequest.builder()
                    .key(blobKey)
                    .bucket(bucketName)
                    .build();
            ResponseBytes<GetObjectResponse> objectBytes = s3client.getObject(objectRequest,
                    ResponseTransformer.toBytes());
            return objectBytes.asByteArray();
        } catch (Exception e) {
            log.error(S3_ERROR_LOG, blobKey, e.getMessage());
            throw new StorageUnavailableException("Failed to download module ZIP for key " + blobKey, e);
        }
    }

}
