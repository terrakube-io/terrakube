package io.terrakube.executor.plugin.tfstate.consul;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.text.TextStringBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orbitz.consul.Consul;
import com.orbitz.consul.KeyValueClient;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.workspace.history.History;
import io.terrakube.client.model.organization.workspace.history.HistoryAttributes;
import io.terrakube.client.model.organization.workspace.history.HistoryRequest;
import io.terrakube.executor.plugin.tfstate.TerraformOutputPathService;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.plugin.tfstate.TerraformStatePathService;
import io.terrakube.executor.service.mode.TerraformJob;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Builder
@Getter
@Setter
@Slf4j
public class ConsulTerraformStateImpl implements TerraformState {
    
    private static final String TERRAFORM_PLAN_FILE = "terraformLibrary.tfPlan";
    private static final String BACKEND_FILE_NAME = "consul_backend_override.tf";
    
    @NonNull
    private Consul consulClient;
    
    @NonNull
    private String address;
    
    private String scheme;
    private String path;
    private String token;
    private String datacenter;
    private boolean gzip;
    private boolean lock;
    
    // Vault integration
    private String vaultAddress;
    private String vaultToken;
    private String vaultTokenPath;
    private boolean useVaultForToken;
    
    @NonNull
    TerraformOutputPathService terraformOutputPathService;
    
    @NonNull
    TerrakubeClient terrakubeClient;
    
    @NonNull
    TerraformStatePathService terraformStatePathService;

    @Override
    public String getBackendStateFile(String organizationId, String workspaceId, File workingDirectory, String terraformVersion) {
        log.info("Generating consul backend override file for terraform {}", terraformVersion);
        
        String consulToken = getConsulToken();
        String consulBackend = BACKEND_FILE_NAME;
        
        try {
            TextStringBuilder consulBackendHcl = new TextStringBuilder();
            consulBackendHcl.appendln("terraform {");
            consulBackendHcl.appendln("  backend \"consul\" {");
            consulBackendHcl.appendln("    address = \"" + address + "\"");
            consulBackendHcl.appendln("    scheme  = \"" + scheme + "\"");
            consulBackendHcl.appendln("    path    = \"" + path + "/" + organizationId + "/" + workspaceId + "/terraform.tfstate\"");
            
            if (consulToken != null && !consulToken.isEmpty()) {
                consulBackendHcl.appendln("    access_token = \"" + consulToken + "\"");
            }
            
            if (datacenter != null && !datacenter.isEmpty()) {
                consulBackendHcl.appendln("    datacenter = \"" + datacenter + "\"");
            }
            
            consulBackendHcl.appendln("    gzip = " + gzip);
            consulBackendHcl.appendln("    lock = " + lock);
            consulBackendHcl.appendln("  }");
            consulBackendHcl.appendln("}");
            
            File consulBackendFile = new File(
                    FilenameUtils.separatorsToSystem(
                            workingDirectory.getAbsolutePath().concat("/").concat(BACKEND_FILE_NAME)
                    )
            );
            FileUtils.writeStringToFile(consulBackendFile, consulBackendHcl.toString(), Charset.defaultCharset());
        } catch (IOException e) {
            log.error(e.getMessage());
            consulBackend = null;
        }
        
        return consulBackend;
    }

    @Override
    public String saveTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory) {
        String consulKey = path + "/" + organizationId + "/" + workspaceId + "/" + jobId + "/" + stepId + "/" + TERRAFORM_PLAN_FILE;
        log.info("Saving terraform plan to consul key: {}", consulKey);
        
        File tfPlanContent = new File(workingDirectory.getAbsolutePath() + "/" + TERRAFORM_PLAN_FILE);
        log.info("Terraform plan file path: {} exists: {}", tfPlanContent.getAbsolutePath(), tfPlanContent.exists());
        
        if (tfPlanContent.exists()) {
            try {
                byte[] planContent = FileUtils.readFileToByteArray(tfPlanContent);
                KeyValueClient kvClient = consulClient.keyValueClient();
                
                // Convert byte array to string for Consul
                String planContentStr = StringUtils.newStringUtf8(planContent);
                kvClient.putValue(consulKey, planContentStr);
                
                return scheme + "://" + address + "/v1/kv/" + consulKey;
            } catch (IOException e) {
                log.error("Error reading terraform plan file: {}", e.getMessage());
                return null;
            }
        } else {
            return null;
        }
    }

    @Override
    public boolean downloadTerraformPlan(String organizationId, String workspaceId, String jobId, String stepId, File workingDirectory) {
        AtomicBoolean planExists = new AtomicBoolean(false);
        
        Optional.ofNullable(terrakubeClient.getJobById(organizationId, jobId).getData().getAttributes().getTerraformPlan())
                .ifPresent(stateUrl -> {
                    try {
                        log.info("Downloading plan from consul: {}", stateUrl);
                        String consulKey = extractConsulKeyFromUrl(stateUrl);
                        
                        KeyValueClient kvClient = consulClient.keyValueClient();
                        Optional<String> planDataStr = kvClient.getValueAsString(consulKey);
                        
                        if (planDataStr.isPresent()) {
                            // Convert string back to bytes
                            byte[] planData = StringUtils.getBytesUtf8(planDataStr.get());
                            FileUtils.writeByteArrayToFile(
                                    new File(workingDirectory.getAbsolutePath() + "/" + TERRAFORM_PLAN_FILE),
                                    planData
                            );
                            planExists.set(true);
                        }
                    } catch (Exception e) {
                        log.error("Error downloading terraform plan: {}", e.getMessage());
                    }
                });
        
        return planExists.get();
    }

    @Override
    public void saveStateJson(TerraformJob terraformJob, String applyJSON, String rawState) {
        if (applyJSON != null) {
            String stateFilename = UUID.randomUUID().toString();
            String consulKey = path + "/" + terraformJob.getOrganizationId() + "/" + terraformJob.getWorkspaceId() + "/state/" + stateFilename + ".json";
            String consulKeyRaw = path + "/" + terraformJob.getOrganizationId() + "/" + terraformJob.getWorkspaceId() + "/state/" + stateFilename + ".raw.json";
            
            log.info("Saving terraform state to consul key: {}", consulKey);
            log.info("Saving raw terraform state to consul key: {}", consulKeyRaw);
            
            try {
                KeyValueClient kvClient = consulClient.keyValueClient();
                kvClient.putValue(consulKey, applyJSON);
                kvClient.putValue(consulKeyRaw, rawState);
                
                String stateURL = terraformStatePathService.getStateJsonPath(terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(), stateFilename);
                
                HistoryRequest historyRequest = new HistoryRequest();
                History newHistory = new History();
                newHistory.setType("history");
                HistoryAttributes historyAttributes = new HistoryAttributes();
                historyAttributes.setOutput(stateURL);
                historyAttributes.setSerial(1);
                historyAttributes.setMd5("0");
                historyAttributes.setLineage("0");
                historyAttributes.setJobReference(terraformJob.getJobId());
                newHistory.setAttributes(historyAttributes);
                historyRequest.setData(newHistory);
                
                terrakubeClient.createHistory(historyRequest, terraformJob.getOrganizationId(), terraformJob.getWorkspaceId());
            } catch (Exception e) {
                log.error("Error saving terraform state: {}", e.getMessage());
            }
        }
    }

    @Override
    public String saveOutput(String organizationId, String jobId, String stepId, String output, String outputError) {
        String consulKey = path + "/output/" + organizationId + "/" + jobId + "/" + stepId + ".tfoutput";
        log.info("Saving output to consul key: {}", consulKey);
        
        try {
            KeyValueClient kvClient = consulClient.keyValueClient();
            kvClient.putValue(consulKey, output + outputError);
            
            log.info("Upload to consul key {} completed", consulKey);
            return terraformOutputPathService.getOutputPath(organizationId, jobId, stepId);
        } catch (Exception e) {
            log.error("Error saving output: {}", e.getMessage());
            return null;
        }
    }
    
    private String getConsulToken() {
        if (!useVaultForToken) {
            return token;
        }
        
        if (vaultAddress == null || vaultToken == null) {
            log.warn("Vault configuration missing, using static token");
            return token;
        }
        
        try {
            log.info("Retrieving consul token from vault");
            String dynamicToken = getTokenFromVault();
            if (dynamicToken != null) {
                log.info("Successfully retrieved consul token from vault");
                return dynamicToken;
            }
        } catch (Exception e) {
            log.error("Error retrieving consul token from vault: {}", e.getMessage());
        }
        
        log.warn("Falling back to static consul token");
        return token;
    }
    
    private String getTokenFromVault() {
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Vault-Token", vaultToken);
            
            HttpEntity<String> entity = new HttpEntity<>(headers);
            String url = vaultAddress + "/v1/" + vaultTokenPath;
            
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode rootNode = mapper.readTree(response.getBody());
                JsonNode dataNode = rootNode.path("data");
                
                if (dataNode.has("token")) {
                    return dataNode.get("token").asText();
                }
            }
        } catch (Exception e) {
            log.error("Error calling Vault API: {}", e.getMessage());
        }
        
        return null;
    }
    
    private String extractConsulKeyFromUrl(String url) {
        try {
            URL parsedUrl = new URL(url);
            String urlPath = parsedUrl.getPath();
            if (urlPath.startsWith("/v1/kv/")) {
                return urlPath.substring("/v1/kv/".length());
            }
            return urlPath;
        } catch (Exception e) {
            log.error("Error extracting consul key from URL: {}", e.getMessage());
            return url;
        }
    }
}