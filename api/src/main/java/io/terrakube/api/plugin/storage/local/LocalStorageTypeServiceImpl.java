package io.terrakube.api.plugin.storage.local;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import io.terrakube.api.plugin.storage.StorageTypeService;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
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

    @Override
    public byte[] getStepOutput(String organizationId, String jobId, String stepId) {
        log.info("Searching: /.terraform-spring-boot/local/tfoutput/{}/{}/{}.tfoutput", organizationId, jobId, stepId);
        String outputFilePath = String.format(OUTPUT_DIRECTORY, organizationId, jobId, stepId);
        return getOutputBytes(outputFilePath);
    }

    @Override
    public void uploadStepOutput(String organizationId, String jobId, String stepId, byte[] output) {
        try {
            String outputFilePath = String.format(OUTPUT_DIRECTORY, organizationId, jobId, stepId);
            log.info("uploadStepOutput {}", outputFilePath);
            File outputFile = new File(FileUtils.getUserDirectoryPath().concat(FilenameUtils.separatorsToSystem(outputFilePath)));
            FileUtils.forceMkdir(outputFile.getParentFile());
            FileUtils.writeByteArrayToFile(outputFile, output);
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    @Override
    public void uploadTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, byte[] planContent) {
        try {
            String planFilePath = String.format(STATE_DIRECTORY, organizationId, workspaceId, jobId, stepId);
            log.info("uploadTerraformPlan {}", planFilePath);
            File planFile = new File(FileUtils.getUserDirectoryPath().concat(FilenameUtils.separatorsToSystem(planFilePath)));
            FileUtils.forceMkdir(planFile.getParentFile());
            FileUtils.writeByteArrayToFile(planFile, planContent);
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    @Override
    public void deleteCurrentTerraformState(String organizationId, String workspaceId) {
        String currentStateFile = String.format(LOCAL_BACKEND_DIRECTORY, organizationId, workspaceId);
        log.info("deleteCurrentTerraformState {}", currentStateFile);
        File stateFile = new File(FileUtils.getUserDirectoryPath().concat(FilenameUtils.separatorsToSystem(currentStateFile)));
        if (stateFile.exists()) {
            FileUtils.deleteQuietly(stateFile);
        }
    }

    @Override
    public byte[] getTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId) {
        log.info("Searching: /.terraform-spring-boot/local/state/{}/{}/{}/{}/terraformLibrary.tfPlan", organizationId, workspaceId, jobId, stepId);
        String outputFilePath = String.format(STATE_DIRECTORY, organizationId, workspaceId, jobId, stepId);
        return getOutputBytes(outputFilePath);
    }

    @Override
    public byte[] getTerraformStateJson(String organizationId, String workspaceId, String stateFileName) {
        log.info("Searching: /.terraform-spring-boot/local/state/{}/{}/state/{}.json", organizationId, workspaceId, stateFileName);
        String outputFilePath = String.format(STATE_DIRECTORY_JSON, organizationId, workspaceId, stateFileName);
        return getOutputBytes(outputFilePath);
    }

    @Override
    public void uploadTerraformStateJson(String organizationId, String workspaceId, String stateJson, String stateJsonHistoryId) {
        try {
            String newStateFileJson = String.format(STATE_DIRECTORY_JSON, organizationId, workspaceId, stateJsonHistoryId);
            log.info("newFileJson: {}", newStateFileJson);
            File stateFile = new File(FileUtils.getUserDirectoryPath().concat(FilenameUtils.separatorsToSystem(newStateFileJson)));
            FileUtils.forceMkdir(stateFile.getParentFile());
            FileUtils.writeStringToFile(stateFile, stateJson, Charset.defaultCharset().toString());
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    @Override
    public byte[] getCurrentTerraformState(String organizationId, String workspaceId) {
        String currentStateFile = String.format(LOCAL_BACKEND_DIRECTORY, organizationId, workspaceId);
        log.info("newFilename: {}", currentStateFile);
        return getOutputBytes(currentStateFile);
    }

    @Override
    public void uploadState(String organizationId, String workspaceId, String terraformState, String historyId) {
        try {
            String newStateFile = String.format(LOCAL_BACKEND_DIRECTORY, organizationId, workspaceId);
            String newRawStateFile = String.format(LOCAL_HISTORY_BACKEND_DIRECTORY, organizationId, workspaceId, historyId);
            log.info("newFilename: {}", newStateFile);
            log.info("newRawFilename: {}", newRawStateFile);
            File stateFile = new File(FileUtils.getUserDirectoryPath().concat(FilenameUtils.separatorsToSystem(newStateFile)));
            File rawStateFile = new File(FileUtils.getUserDirectoryPath().concat(FilenameUtils.separatorsToSystem(newRawStateFile)));
            FileUtils.forceMkdir(stateFile.getParentFile());
            FileUtils.forceMkdir(rawStateFile.getParentFile());
            FileUtils.writeStringToFile(stateFile, terraformState, Charset.defaultCharset().toString());
            FileUtils.writeStringToFile(rawStateFile, terraformState, Charset.defaultCharset().toString());
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    @Override
    public String saveContext(int jobId, String jobContext) {
        try {
            String contextFilename = String.format(CONTEXT_DIRECTORY, jobId);
            log.info("contextFile: {}", contextFilename);
            File context = new File(FileUtils.getUserDirectoryPath().concat(FilenameUtils.separatorsToSystem(contextFilename)));
            FileUtils.forceMkdir(context.getParentFile());
            FileUtils.writeStringToFile(context, jobContext, Charset.defaultCharset().toString());
        } catch (IOException e) {
            log.error(e.getMessage());
        }

        return jobContext;
    }

    @Override
    public String getContext(int jobId) {
        String searchContextFile = String.format(CONTEXT_DIRECTORY, jobId);
        log.info("contextFile: {}", searchContextFile);
        File context = new File(FileUtils.getUserDirectoryPath().concat(FilenameUtils.separatorsToSystem(searchContextFile)));
        String outputContext = NO_CONTEXT_FOUND;
        if (context.exists()) {
            try {
                outputContext = new String(IOUtils.toByteArray(new FileInputStream(context)));
            } catch (IOException e) {
                log.error(e.getMessage());
            }
        }

        return outputContext;
    }

    private byte [] getOutputBytes(String path){
        File localOutputDirectory = new File(FileUtils.getUserDirectoryPath().concat(path));
        if (localOutputDirectory.exists()) {
            try {
                return IOUtils.toByteArray(new FileInputStream(localOutputDirectory));
            } catch (IOException e) {
                log.error(e.getMessage());
                return new byte[0];
            }
        } else {
            return new byte[0];
        }
    }


    @Override
    public void createContentFile(String contentId, InputStream inputStream){
        try {
            String contentFile = String.format(CONTENT_DIRECTORY, contentId);
            log.info("contentFile: {}", contentFile);
            File context = new File(FileUtils.getUserDirectoryPath().concat(FilenameUtils.separatorsToSystem(contentFile)));
            FileUtils.forceMkdir(context.getParentFile());
            FileUtils.writeByteArrayToFile(context, inputStream.readAllBytes());
            log.info("Write File Completed", contentFile);
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    @Override
    public byte[] getContentFile(String contentId) {
        String contentFile = String.format(CONTENT_DIRECTORY, contentId);
        log.info("contentFile: {}", contentFile);
        File content = new File(FileUtils.getUserDirectoryPath().concat(FilenameUtils.separatorsToSystem(contentFile)));
        if (content.exists()) {
            try {
                return IOUtils.toByteArray(new FileInputStream(content));
            } catch (IOException e) {
                log.error(e.getMessage());
                return NO_DATA_FOUND.getBytes(StandardCharsets.UTF_8);
            }
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
}
