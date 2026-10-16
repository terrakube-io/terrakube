package io.terrakube.executor.plugin.tfstate.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.service.mode.TerraformJob;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@Slf4j
@Builder
public class ApiTerraformStateImpl implements TerraformState {

    static final String BACKEND_FILE_NAME = "terrakube_backend_override.tf";
    private static final String TERRAFORM_PLAN_FILE = "terraformLibrary.tfPlan";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @NonNull
    private RunnerApiClient runnerApiClient;
    @NonNull
    private TerrakubeClient terrakubeClient;
    @NonNull
    private TerraformStatePathService terraformStatePathService;
    @NonNull
    private TerraformOutputPathService terraformOutputPathService;

    @Override
    public String getBackendStateFile(String organizationId, String workspaceId, File workingDirectory, String terraformVersion) {
        String hcl = String.join("\n",
                "terraform {",
                "  backend \"http\" {",
                "    address        = " + quote(runnerApiClient.url("/state")),
                "    lock_address   = " + quote(runnerApiClient.url("/lock")),
                "    unlock_address = " + quote(runnerApiClient.url("/lock")),
                "    lock_method    = \"POST\"",
                "    unlock_method  = \"DELETE\"",
                "  }",
                "}",
                "");
        try {
            FileUtils.writeStringToFile(new File(workingDirectory, BACKEND_FILE_NAME), hcl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return BACKEND_FILE_NAME;
    }

    @Override
    public String saveTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory) {
        File plan = new File(workingDirectory, TERRAFORM_PLAN_FILE);
        if (!plan.exists()) {
            return null;
        }
        try {
            runnerApiClient.put("/plan", Files.readAllBytes(plan.toPath()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return terraformStatePathService.getTerraformBinaryPlanPath(organizationId, workspaceId, jobId, stepId);
    }

    @Override
    public boolean downloadTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory) {
        String planUrl = terrakubeClient.getJobById(organizationId, jobId).getData().getAttributes().getTerraformPlan();
        if (planUrl == null || planUrl.isBlank()) {
            return false;
        }
        String expectedPrefix = terraformStatePathService.getTerraformBinaryPlanPath(organizationId, workspaceId, jobId, "");
        expectedPrefix = expectedPrefix.substring(0, expectedPrefix.length() - "/terraform.tfstate".length());
        if (!planUrl.startsWith(expectedPrefix)) {
            log.error("Refusing to download plan from unexpected location {}", planUrl);
            return false;
        }
        byte[] plan = runnerApiClient.get(planUrl);
        if (plan == null || plan.length == 0) {
            return false;
        }
        try {
            FileUtils.writeByteArrayToFile(new File(workingDirectory, TERRAFORM_PLAN_FILE), plan);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return true;
    }

    @Override
    public void saveStateJson(TerraformJob terraformJob, String applyJSON, String rawState) {
        if (applyJSON == null) {
            return;
        }
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("json", applyJSON);
        body.put("raw", rawState);
        runnerApiClient.postJson("/history", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String saveOutput(String organizationId, String jobId, String stepId, String output, String outputError) {
        runnerApiClient.put("/output", (output + outputError).getBytes(StandardCharsets.UTF_8));
        return terraformOutputPathService.getOutputPath(organizationId, jobId, stepId);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("${", "$${") + "\"";
    }
}
