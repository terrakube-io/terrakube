package io.terrakube.executor.plugin.tfstate.http;

import io.terrakube.executor.plugin.tfstate.ArtifactVerificationException;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.service.logs.StepOutputBuffer;
import io.terrakube.executor.service.logs.StepOutputPartWriter;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.text.TextStringBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

@Slf4j
@Builder
@Getter
@Setter
public class HttpTerraformStateImpl implements TerraformState {

    private static final String BACKEND_FILE_NAME = "terrakube_override.tf";
    private static final String HTTP_BACKEND_USERNAME = "terraform";
    private static final String TF_HTTP_USERNAME = "TF_HTTP_USERNAME";
    private static final String TF_HTTP_PASSWORD = "TF_HTTP_PASSWORD";

    @Builder.Default
    private int tokenTtlMinutes = 60;

    @NonNull
    private TerraformStatePathService terraformStatePathService;

    @Builder.Default
    private WorkspaceSecurity workspaceSecurity = null;

    @Builder.Default
    private TerraformState delegate = null;

    @Override
    public String getBackendStateFile(String organizationId, String workspaceId, File workingDirectory, String terraformVersion) {
        return getBackendStateFile(organizationId, workspaceId, null, null, workingDirectory, terraformVersion);
    }

    @Override
    public String getBackendStateFile(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory, String terraformVersion) {
        log.info("Generating HTTP backend override file for terraform {}", terraformVersion);
        String httpBackend = BACKEND_FILE_NAME;
        try {
            String backendAddress = terraformStatePathService.getStateHttpBackendPath(organizationId, workspaceId);

            TextStringBuilder httpBackendHcl = new TextStringBuilder();
            httpBackendHcl.appendln("terraform {");
            httpBackendHcl.appendln("  backend \"http\" {");
            httpBackendHcl.appendln("    address  = \"" + backendAddress + "\"");
            httpBackendHcl.appendln("  }");
            httpBackendHcl.appendln("}");

            File httpBackendFile = new File(
                    FilenameUtils.separatorsToSystem(
                            String.join(File.separator, Stream.of(workingDirectory.getAbsolutePath(), BACKEND_FILE_NAME)
                                    .toArray(String[]::new))));

            String content = httpBackendHcl.toString();
            if (httpBackendFile.exists()) {
                try {
                    String existingContent = FileUtils.readFileToString(httpBackendFile, StandardCharsets.UTF_8);
                    if (existingContent.equals(content)) {
                        log.debug("HTTP Backend File already up-to-date: {}", httpBackendFile.getAbsolutePath());
                        return BACKEND_FILE_NAME;
                    }
                } catch (IOException e) {
                    log.warn("Could not read existing HTTP backend override file, will overwrite: {}", e.getMessage());
                }
            }

            log.info("Creating HTTP Backend File: {}", httpBackendFile.getAbsolutePath());
            FileUtils.writeStringToFile(httpBackendFile, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to generate HTTP backend override file: {}", e.getMessage());
            httpBackend = null;
        }
        return httpBackend;
    }

    private String resolveScopedToken(String organizationId, String workspaceId, String jobId, String stepId) {
        if (workspaceSecurity == null) {
            return "";
        }
        String token = workspaceSecurity.generateAccessToken(tokenTtlMinutes, organizationId, workspaceId, jobId, stepId);
        return token != null ? token : "";
    }

    @Override
    public Map<String, String> getBackendEnvironmentVariables(String organizationId, String workspaceId, String jobId, String stepId) {
        Map<String, String> env = new HashMap<>();
        if (delegate != null) {
            env.putAll(delegate.getBackendEnvironmentVariables(organizationId, workspaceId, jobId, stepId));
        }
        String accessToken = resolveScopedToken(organizationId, workspaceId, jobId, stepId);
        if (accessToken != null && !accessToken.isBlank()) {
            env.put(TF_HTTP_USERNAME, HTTP_BACKEND_USERNAME);
            env.put(TF_HTTP_PASSWORD, accessToken);
        }
        return env;
    }

    @Override
    public String saveTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory) {
        return delegate != null ? delegate.saveTerraformPlan(organizationId, workspaceId, jobId, stepId, workingDirectory) : null;
    }

    @Override
    public boolean downloadTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory) {
        return delegate != null && delegate.downloadTerraformPlan(organizationId, workspaceId, jobId, stepId, workingDirectory);
    }

    @Override
    public String saveArtifacts(String organizationId, String workspaceId, String jobId, String stepId, File bundle) {
        return delegate != null ? delegate.saveArtifacts(organizationId, workspaceId, jobId, stepId, bundle) : null;
    }

    @Override
    public String downloadArtifacts(String organizationId, String workspaceId, String jobId, File target) throws ArtifactVerificationException {
        return delegate != null ? delegate.downloadArtifacts(organizationId, workspaceId, jobId, target) : null;
    }

    @Override
    public void deleteArtifacts(String organizationId, String workspaceId, String jobId) {
        if (delegate != null) {
            delegate.deleteArtifacts(organizationId, workspaceId, jobId);
        }
    }

    @Override
    public void saveStateJson(TerraformJob terraformJob, String applyJSON, String rawState) {
        if (delegate != null) {
            delegate.saveStateJson(terraformJob, applyJSON, rawState);
        }
    }

    @Override
    public String saveBufferedOutput(String organizationId, String jobId, String stepId, StepOutputBuffer output, String outputError) {
        return delegate != null ? delegate.saveBufferedOutput(organizationId, jobId, stepId, output, outputError) : null;
    }

    @Override
    public boolean saveOutputPart(String organizationId, String jobId, String stepId, int partNumber, StepOutputPartWriter.PartSource data) throws IOException {
        return delegate != null && delegate.saveOutputPart(organizationId, jobId, stepId, partNumber, data);
    }

    @Override
    public void deleteOutputParts(String organizationId, String jobId, String stepId) {
        if (delegate != null) {
            delegate.deleteOutputParts(organizationId, jobId, stepId);
        }
    }

    @Override
    public boolean saveTerraformBinary(String version, boolean tofu, File binaryFile) {
        return delegate != null && delegate.saveTerraformBinary(version, tofu, binaryFile);
    }

    @Override
    public boolean downloadTerraformBinary(String version, boolean tofu, File targetFile) {
        return delegate != null && delegate.downloadTerraformBinary(version, tofu, targetFile);
    }

    @Override
    public boolean saveOpaBinary(String version, String os, String arch, File sourceFile) {
        return delegate != null && delegate.saveOpaBinary(version, os, arch, sourceFile);
    }

    @Override
    public boolean downloadOpaBinary(String version, String os, String arch, File targetFile) {
        return delegate != null && delegate.downloadOpaBinary(version, os, arch, targetFile);
    }

    @Override
    public String spoolContextPatch(String organizationId, String jobId, String stepId, String phase, byte[] data) {
        return delegate != null ? delegate.spoolContextPatch(organizationId, jobId, stepId, phase, data) : null;
    }
}

