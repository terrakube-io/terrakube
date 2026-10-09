package io.terrakube.api.plugin.storage.local;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BoundedInputStream;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.storage.StorageUnavailableException;
import io.terrakube.api.plugin.storage.model.ByteRange;
import io.terrakube.api.plugin.storage.model.StepOutputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@AllArgsConstructor
@Builder
public class LocalStorageTypeServiceImpl implements StorageTypeService {

    private static final String OUTPUT_DIRECTORY = "/.terraform-spring-boot/local/output/%s/%s/%s.tfoutput";
    private static final String CONTENT_DIRECTORY = "/.terraform-spring-boot/local/content/%s/terraformContent.tar.gz";
    private static final String CONTEXT_DIRECTORY = "/.terraform-spring-boot/local/output/context/%s/context.json";
    private static final String STATE_DIRECTORY = "/.terraform-spring-boot/local/state/%s/%s/%s/%s/terraformLibrary.tfPlan";
    private static final String STATE_DIRECTORY_JSON = "/.terraform-spring-boot/local/state/%s/%s/state/%s.json";
    private static final String NO_DATA_FOUND = "";
    private static final String NO_CONTEXT_FOUND = "{}";
    private static final String LOCAL_BACKEND_DIRECTORY = "/.terraform-spring-boot/local/backend/%s/%s/terraform.tfstate";
    private static final String LOCAL_HISTORY_BACKEND_DIRECTORY = "/.terraform-spring-boot/local/state/%s/%s/state/%s.raw.json";

    // Short, bounded retry for a genuine I/O failure on the single underlying call - mirrors the
    // AWS backend's retry window (#3671).
    private static final int STORAGE_MAX_ATTEMPTS = 3;
    private static final long[] STORAGE_BACKOFF_MILLIS = {200, 500};

    // File.exists() returning false is the not-found signal, unchanged from before. An
    // IOException reading a file that does exist is a genuine failure (disk, permissions) -
    // retried, then thrown as StorageUnavailableException instead of silently collapsing into
    // the same empty result as a real "not found" (#3671).
    private byte[] readFile(String path) {
        File file = new File(FileUtils.getUserDirectoryPath().concat(path));
        if (!file.exists()) {
            return new byte[0];
        }
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= STORAGE_MAX_ATTEMPTS; attempt++) {
            try {
                return IOUtils.toByteArray(new FileInputStream(file));
            } catch (IOException e) {
                lastFailure = e;
                if (attempt < STORAGE_MAX_ATTEMPTS) {
                    log.warn("Local read attempt {} failed for {}, retrying: {}", attempt, path, e.getMessage());
                    sleepBackoff(STORAGE_BACKOFF_MILLIS[attempt - 1]);
                }
            }
        }
        throw new StorageUnavailableException("Local read failed for " + path, lastFailure);
    }

    // Shared by every write path (state, context, policy evaluation, and the CLI-driven
    // configuration tarball) - a failed write now fails the caller instead of logging and
    // returning as if it succeeded (#3671).
    private void writeFile(String path, byte[] data) {
        File file = new File(FileUtils.getUserDirectoryPath().concat(FilenameUtils.separatorsToSystem(path)));
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= STORAGE_MAX_ATTEMPTS; attempt++) {
            try {
                FileUtils.forceMkdir(file.getParentFile());
                FileUtils.writeByteArrayToFile(file, data);
                log.info("Write file {} completed", path);
                return;
            } catch (IOException e) {
                lastFailure = e;
                if (attempt < STORAGE_MAX_ATTEMPTS) {
                    log.warn("Local write attempt {} failed for {}, retrying: {}", attempt, path, e.getMessage());
                    sleepBackoff(STORAGE_BACKOFF_MILLIS[attempt - 1]);
                }
            }
        }
        throw new StorageUnavailableException("Local write failed for " + path, lastFailure);
    }

    private void sleepBackoff(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public byte[] getStepOutput(String organizationId, String jobId, String stepId) {
        log.info("Searching: /.terraform-spring-boot/local/tfoutput/{}/{}/{}.tfoutput", organizationId, jobId, stepId);
        return readFile(String.format(OUTPUT_DIRECTORY, organizationId, jobId, stepId));
    }

    @Override
    public StepOutputStream getStepOutputStream(String organizationId, String jobId, String stepId, ByteRange range) {
        String path = String.format(OUTPUT_DIRECTORY, organizationId, jobId, stepId);
        File file = new File(FileUtils.getUserDirectoryPath().concat(path));
        if (!file.exists()) {
            return StepOutputStream.missing();
        }
        long total = file.length();
        try {
            if (range == null) {
                return StepOutputStream.of(new FileInputStream(file), total, total);
            }
            long start = range.isSuffix() ? Math.max(0, total - range.getSuffixLength()) : range.getStart();
            long endInclusive = range.isSuffix() ? total - 1
                    : (range.getEnd() >= 0 ? Math.min(range.getEnd(), total - 1) : total - 1);
            if (start >= total) {
                return StepOutputStream.missing();
            }
            long partLength = endInclusive - start + 1;
            FileInputStream fis = new FileInputStream(file);
            IOUtils.skipFully(fis, start);
            InputStream bounded = BoundedInputStream.builder().setInputStream(fis).setMaxCount(partLength).get();
            String contentRange = "bytes " + start + "-" + endInclusive + "/" + total;
            return StepOutputStream.partial(bounded, partLength, contentRange, total);
        } catch (IOException e) {
            log.error("Failed to open local step output {}: {}", path, e.getMessage());
            return StepOutputStream.missing();
        }
    }

    @Override
    public byte[] getTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId) {
        log.info("Searching: /.terraform-spring-boot/local/state/{}/{}/{}/{}/terraformLibrary.tfPlan", organizationId, workspaceId, jobId, stepId);
        return readFile(String.format(STATE_DIRECTORY, organizationId, workspaceId, jobId, stepId));
    }

    @Override
    public byte[] getTerraformStateJson(String organizationId, String workspaceId, String stateFileName) {
        log.info("Searching: /.terraform-spring-boot/local/state/{}/{}/state/{}.json", organizationId, workspaceId, stateFileName);
        return readFile(String.format(STATE_DIRECTORY_JSON, organizationId, workspaceId, stateFileName));
    }

    @Override
    public void uploadTerraformStateJson(String organizationId, String workspaceId, String stateJson, String stateJsonHistoryId) {
        String newStateFileJson = String.format(STATE_DIRECTORY_JSON, organizationId, workspaceId, stateJsonHistoryId);
        log.info("newFileJson: {}", newStateFileJson);
        writeFile(newStateFileJson, stateJson.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public byte[] getCurrentTerraformState(String organizationId, String workspaceId) {
        String currentStateFile = String.format(LOCAL_BACKEND_DIRECTORY, organizationId, workspaceId);
        log.info("newFilename: {}", currentStateFile);
        return readFile(currentStateFile);
    }

    @Override
    public void uploadState(String organizationId, String workspaceId, String terraformState, String historyId) {
        String newStateFile = String.format(LOCAL_BACKEND_DIRECTORY, organizationId, workspaceId);
        String newRawStateFile = String.format(LOCAL_HISTORY_BACKEND_DIRECTORY, organizationId, workspaceId, historyId);
        log.info("newFilename: {}", newStateFile);
        log.info("newRawFilename: {}", newRawStateFile);
        byte[] data = terraformState.getBytes(StandardCharsets.UTF_8);
        writeFile(newStateFile, data);
        writeFile(newRawStateFile, data);
    }

    @Override
    public String saveContext(int jobId, String jobContext) {
        String contextFilename = String.format(CONTEXT_DIRECTORY, jobId);
        log.info("contextFile: {}", contextFilename);
        writeFile(contextFilename, jobContext.getBytes(StandardCharsets.UTF_8));
        return jobContext;
    }

    @Override
    public String getContext(int jobId) {
        String searchContextFile = String.format(CONTEXT_DIRECTORY, jobId);
        log.info("contextFile: {}", searchContextFile);
        byte[] bytes = readFile(searchContextFile);
        if (bytes != null && bytes.length > 0) {
            return new String(bytes, StandardCharsets.UTF_8);
        } else {
            return NO_CONTEXT_FOUND;
        }
    }

    @Override
    public void createContentFile(String contentId, InputStream inputStream){
        String contentFile = String.format(CONTENT_DIRECTORY, contentId);
        log.info("contentFile: {}", contentFile);

        byte[] content;
        try {
            content = inputStream.readAllBytes();
        } catch (IOException e) {
            // Reading the upload's own request body failed - can't be retried (it's a one-shot
            // stream), so fail the upload instead of silently marking it uploaded (#3671).
            throw new StorageUnavailableException("Unable to read uploaded content for " + contentId, e);
        }

        writeFile(contentFile, content);
    }

    @Override
    public byte[] getContentFile(String contentId) {
        String contentFile = String.format(CONTENT_DIRECTORY, contentId);
        log.info("contentFile: {}", contentFile);
        byte[] bytes = readFile(contentFile);
        if (bytes != null && bytes.length > 0) {
            return bytes;
        } else {
            return NO_DATA_FOUND.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public void deleteModuleStorage(String organizationName, String moduleName, String providerName) {
        try {
            String registryPath = String.format("%s/.terraform-spring-boot/local/modules/%s/%s/%s", FileUtils.getUserDirectoryPath(), organizationName, moduleName, providerName);
            log.warn("Delete module folder: {}", registryPath);
            FileUtils.cleanDirectory(new File(registryPath));
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    @Override
    public void deleteWorkspaceOutputData(String organizationId, List<Integer> jobList) {
        try {
            for (Integer jobId : jobList) {
                String workspaceOutputFolder = String.format("%s/.terraform-spring-boot/local/output/%s/%s", FileUtils.getUserDirectoryPath(), organizationId, jobId);
                log.warn("Delete workspace output folder: {}", workspaceOutputFolder);
                FileUtils.cleanDirectory(new File(workspaceOutputFolder));
            }
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    @Override
    public void deleteWorkspaceStateData(String organizationId, String workspaceId) {
        try {
            String statePath = String.format("%s/.terraform-spring-boot/local/state/%s/%s", FileUtils.getUserDirectoryPath(), organizationId, workspaceId);
            log.warn("Delete workspace state folder: {}", statePath);
            FileUtils.cleanDirectory(new File(statePath));

            statePath = String.format("%s/.terraform-spring-boot/local/backend/%s/%s/", FileUtils.getUserDirectoryPath(), organizationId, workspaceId);
            log.warn("Delete workspace state folder: {}", statePath);
            FileUtils.cleanDirectory(new File(statePath));
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    @Override
    public boolean migrateToOrganization(String organizationId, String workspaceId, String migrateToOrganizationId) {

        String sourceOutputDirectory = String.format("%s/.terraform-spring-boot/local/output/%s/%s", FileUtils.getUserDirectoryPath(), organizationId, workspaceId);
        String sourceOutputTarget = String.format("%s/.terraform-spring-boot/local/output/%s", FileUtils.getUserDirectoryPath(), migrateToOrganizationId);
        migrateDirectory(new File(sourceOutputDirectory), new File(sourceOutputTarget));

        String stateDirectory = String.format("%s/.terraform-spring-boot/local/state/%s/%s", FileUtils.getUserDirectoryPath(), organizationId, workspaceId);
        String stateTargetDirectory = String.format("%s/.terraform-spring-boot/local/state/%s", FileUtils.getUserDirectoryPath(), migrateToOrganizationId);
        migrateDirectory(new File(stateDirectory), new File(stateTargetDirectory));

        String terraformStateDirectory = String.format("%s/.terraform-spring-boot/local/backend/%s/%s", FileUtils.getUserDirectoryPath(), organizationId, workspaceId);
        String terraformStateTargetDirectory = String.format("%s/.terraform-spring-boot/local/backend/%s", FileUtils.getUserDirectoryPath(), migrateToOrganizationId);
        migrateDirectory(new File(terraformStateDirectory), new File(terraformStateTargetDirectory));

        return true;
    }

    public void migrateDirectory(File sourceDirectory, File targetDirectory) {
        try {
            FileUtils.moveToDirectory(sourceDirectory, targetDirectory, true);
            log.info("Moving folder {} to {} successfully!", sourceDirectory.getAbsolutePath(), targetDirectory.getAbsolutePath());
        } catch (IOException e) {
            log.info("An error occurred while copying the folder {}: {}",sourceDirectory.getAbsolutePath(), e.getMessage());
        }
    }

    @Override
    public void uploadPolicyEvaluation(String storageUri, String policyEvaluationJson) {
        String relativePath = storageUri.startsWith("/") ? storageUri.substring(1) : storageUri;
        String path = "/.terraform-spring-boot/local/" + relativePath;
        writeFile(path, policyEvaluationJson.getBytes(StandardCharsets.UTF_8));
        log.info("Policy evaluation saved to local storage: {}", path);
    }

    @Override
    public String getPolicyEvaluation(String storageUri) {
        String relativePath = storageUri.startsWith("/") ? storageUri.substring(1) : storageUri;
        String path = "/.terraform-spring-boot/local/" + relativePath;
        byte[] bytes = readFile(path);
        if (bytes != null && bytes.length > 0) {
            return new String(bytes, StandardCharsets.UTF_8);
        } else {
            return "{}";
        }
    }

    @Override
    public void deletePolicyEvaluation(String storageUri) {
        try {
            String relativePath = storageUri.startsWith("/") ? storageUri.substring(1) : storageUri;
            String path = "/.terraform-spring-boot/local/" + relativePath;
            File file = new File(FileUtils.getUserDirectoryPath().concat(FilenameUtils.separatorsToSystem(path)));
            if (file.exists()) {
                boolean deleted = file.delete();
                log.info("Policy evaluation file deleted: {}, success: {}", file.getAbsolutePath(), deleted);
                File parent = file.getParentFile();
                if (parent != null && parent.isDirectory()) {
                    File[] children = parent.listFiles();
                    if (children != null && children.length == 0) {
                        FileUtils.deleteDirectory(parent);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to delete policy evaluation from local storage: {}", e.getMessage());
        }
    }
}
