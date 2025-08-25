package io.terrakube.api.plugin.scheduler.job.tcl.executor;

import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.*;

import io.terrakube.api.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.reactive.function.client.WebClient;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ephemeral.EphemeralExecutorService;
import io.terrakube.api.plugin.scheduler.job.tcl.model.Flow;
import io.terrakube.api.plugin.token.dynamic.DynamicCredentialsService;
import io.terrakube.api.plugin.vcs.TokenService;
import io.terrakube.api.rs.collection.Collection;
import io.terrakube.api.rs.collection.Reference;
import io.terrakube.api.rs.collection.item.Item;
import io.terrakube.api.rs.globalvar.Globalvar;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.address.Address;
import io.terrakube.api.rs.job.address.AddressType;
import io.terrakube.api.rs.ssh.Ssh;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.workspace.parameters.Category;
import io.terrakube.api.rs.workspace.parameters.Variable;

import com.fasterxml.jackson.core.JsonProcessingException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class ExecutorService {

    @Value("${io.terrakube.executor.url}")
    private String executorUrl;

    @Value("${io.terrakube.hostname}")
    String hostname;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    GlobalVarRepository globalVarRepository;

    @Autowired
    SshRepository sshRepository;

    @Autowired
    VcsRepository vcsRepository;

    @Autowired
    DynamicCredentialsService dynamicCredentialsService;

    @Autowired
    EphemeralExecutorService ephemeralExecutorService;

    @Autowired
    TokenService tokenService;
    @Autowired
    private VariableRepository variableRepository;
    @Autowired
    private ReferenceRepository referenceRepository;
    @Autowired
    private WebClient webClient;
    
    @Transactional
    public ExecutorContext execute(Job job, String stepId, Flow flow) {
        log.info("Pending Job: {} WorkspaceId: {}", job.getId(), job.getWorkspace().getId());

        ExecutorContext executorContext = new ExecutorContext();
        executorContext.setOrganizationId(job.getOrganization().getId().toString());
        executorContext.setWorkspaceId(job.getWorkspace().getId().toString());
        executorContext.setJobId(String.valueOf(job.getId()));
        executorContext.setStepId(stepId);

        if (job.getWorkspace().getBranch().equals("remote-content")) {
            log.warn("Running remote operation, disable headers");
            executorContext.setShowHeader(false);
        } else {
            log.warn("Running default operation, enable headers");
            executorContext.setShowHeader(true);
        }

        log.info("Checking Variables");
        if (job.getWorkspace().getVcs() != null) {
            Vcs vcs = job.getWorkspace().getVcs();
            executorContext.setVcsType(vcs.getVcsType().toString());
            executorContext.setConnectionType(vcs.getConnectionType().toString());
            try {
                executorContext.setAccessToken(tokenService.getAccessToken(job.getWorkspace().getSource(), vcs));
            } catch (JsonProcessingException | NoSuchAlgorithmException | InvalidKeySpecException
                    | URISyntaxException e) {
                log.error("Failed to fetch access token for job {} on workspace {}, error {}", job.getId(),
                        job.getWorkspace().getName(), e);
            }
            log.info("Private Repository {}", executorContext.getVcsType());
        } else if (job.getWorkspace().getSsh() != null) {
            Ssh ssh = job.getWorkspace().getSsh();
            executorContext.setVcsType(String.format("SSH~%s", ssh.getSshType().getFileName()));
            executorContext.setAccessToken(ssh.getPrivateKey());
            log.info("Private Repository using SSH private key");
        } else {
            executorContext.setVcsType("PUBLIC");
            log.info("Public Repository");
        }

        HashMap<String, String> terraformVariables = new HashMap<>();
        HashMap<String, String> environmentVariables = new HashMap<>();
        environmentVariables.put("TF_IN_AUTOMATION", "1");
        environmentVariables.put("workspaceName", job.getWorkspace().getName());
        environmentVariables.put("organizationName", job.getOrganization().getName());
        List<Variable> variableList = variableRepository.findByWorkspace(job.getWorkspace()).orElse(new ArrayList<>());
        for (Variable variable : variableList) {
            if (variable.getCategory().equals(Category.TERRAFORM)) {
                log.info("Adding terraform");
                terraformVariables.put(variable.getKey(), variable.getValue());
            } else {
                log.info("Adding environment variable");
                environmentVariables.put(variable.getKey(), variable.getValue());
            }
            log.info("Variable Key: {} Value {}", variable.getKey(),
                    variable.isSensitive() ? "sensitive" : variable.getValue());
        }

        environmentVariables = loadOtherEnvironmentVariables(job, flow, environmentVariables);
        terraformVariables = loadOtherTerraformVariables(job, flow, terraformVariables);

        executorContext.setVariables(terraformVariables);
        executorContext.setEnvironmentVariables(environmentVariables);

        executorContext.setCommandList(flow.getCommands());
        executorContext.setType(flow.getType());
        executorContext.setIgnoreError(flow.isIgnoreError());
        executorContext.setTerraformVersion(job.getWorkspace().getTerraformVersion());
        if (job.getOverrideSource() == null) {
            executorContext.setSource(job.getWorkspace().getSource());
        } else {
            executorContext.setSource(job.getOverrideSource());
        }
        if (job.getOverrideBranch() == null) {
            executorContext.setBranch(job.getWorkspace().getBranch().split(",")[0]);
        } else {
            if (job.getOverrideBranch().equals("remote-content")) {
                executorContext.setShowHeader(false);
            }
            executorContext.setBranch(job.getOverrideBranch());
        }

        if (job.getWorkspace().getModuleSshKey() != null) {
            String moduleSshId = job.getWorkspace().getModuleSshKey();
            Optional<Ssh> ssh = sshRepository.findById(UUID.fromString(moduleSshId));
            if (ssh.isPresent()) {
                executorContext.setModuleSshKey(ssh.get().getPrivateKey());
            }
        }
        executorContext.setTofu(iacType(job));
        executorContext.setCommitId(job.getCommitId());
        executorContext
                .setFolder(job.getWorkspace().getFolder() != null ? job.getWorkspace().getFolder().split(",")[0] : "/");
        executorContext.setRefresh(job.isRefresh());
        executorContext.setRefreshOnly(job.isRefreshOnly());
        executorContext.setAgentUrl(getExecutorUrl(job));
        executorContext = validateJobAddress(executorContext, job);
        return executorContext.getEnvironmentVariables().containsKey("TERRAKUBE_ENABLE_EPHEMERAL_EXECUTOR")
                ? ephemeralExecutorService.sendToEphemeralExecutor(job, executorContext)
                : sendToExecutor(job, executorContext);
    }

    private ExecutorContext validateJobAddress(ExecutorContext executorContext, Job job) {
        if (job.getAddress() != null && !job.getAddress().isEmpty() && (job.getTerraformPlan() == null || job.getTerraformPlan().isEmpty())) {
            List<Address> addressList = job.getAddress();
            StringBuilder tfCliArgsPlan= new StringBuilder();
            for(Address address : addressList) {
                if (address.getType().equals(AddressType.TARGET)) {
                    tfCliArgsPlan.append(String.format(" -target=\"%s\"", address.getName()));
                }

                if (address.getType().equals(AddressType.REPLACE)) {
                    tfCliArgsPlan.append(String.format(" -replace=\"%s\"", address.getName()));
                }
            }

            if(!tfCliArgsPlan.isEmpty()) {
                log.info("Adding TF_CLI_ARGS_PLAN to environment variables: {}", tfCliArgsPlan.toString());
                executorContext.getEnvironmentVariables().putIfAbsent("TF_CLI_ARGS_plan", tfCliArgsPlan.toString());
            }
        }

        return executorContext;
    }

    private String getExecutorUrl(Job job) {
        String agentUrl = job.getWorkspace().getAgent() != null
                ? job.getWorkspace().getAgent().getUrl() + "/api/v1/terraform-rs"
                : validateDefaultExecutor(job);
        log.info("Job {} Executor agent url: {}", job.getId(), agentUrl);
        return agentUrl;
    }

    private String validateDefaultExecutor(Job job) {
        Optional<Globalvar> defaultExecutor = globalVarRepository.findByOrganizationAndKey(job.getOrganization(), "TERRAKUBE_DEFAULT_EXECUTOR");
        if (defaultExecutor.isPresent()) {
            log.info("Found default executor url {}", defaultExecutor.get().getValue());
            return defaultExecutor.get().getValue() + "/api/v1/terraform-rs";
        } else {
            log.info("No default executor found, using default executor url {}", this.executorUrl);
            return this.executorUrl;
        }
    }

    private boolean iacType(Job job) {
        return job.getWorkspace().getIacType() != null && job.getWorkspace().getIacType().equals("terraform") ? false
                : true;
    }

    private ExecutorContext sendToExecutor(Job job, ExecutorContext executorContext) {
        boolean executed = false;
        try {
            ResponseEntity<ExecutorContext> response = webClient.post()
                    .uri(getExecutorUrl(job))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(executorContext)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            clientResponse -> clientResponse.createException().flatMap(Mono::error))
                    .toEntity(ExecutorContext.class)
                    .block();

            executorContext.setAccessToken("****");
            executorContext.setModuleSshKey("****");
            log.debug("Sending Job: /n {}", executorContext);
            log.info("Response Status: {}", response.getStatusCode().value());

            if (response.getStatusCode().equals(HttpStatus.ACCEPTED)) {
                job.setStatus(JobStatus.queue);
                jobRepository.save(job);
                executed = true;
            } else {
                executed = false;
            }
        } catch (RestClientException ex) {
            log.error(ex.getMessage());
            executed = false;
    }

    return executed ? executorContext : null;
}

    private HashMap<String, String> loadOtherEnvironmentVariables(Job job, Flow flow,
            HashMap<String, String> workspaceEnvVariables) {
        if (flow.getInputsEnv() != null
                || (flow.getImportComands() != null && flow.getImportComands().getInputsEnv() != null)) {
            if (flow.getImportComands() != null && flow.getImportComands().getInputsEnv() != null) {
                log.info("Loading ENV inputs from ImportComands");
                workspaceEnvVariables = loadInputData(job, Category.ENV,
                        new HashMap(flow.getImportComands().getInputsEnv()), workspaceEnvVariables);
            }

            if (flow.getInputsEnv() != null) {
                log.info("Loading ENV inputs from InputsEnv");
                workspaceEnvVariables = loadInputData(job, Category.ENV, new HashMap(flow.getInputsEnv()),
                        workspaceEnvVariables);
            }

        } else {
            log.info("Loading default env variables to job");
            workspaceEnvVariables = loadDefault(job, Category.ENV, workspaceEnvVariables);
        }

        if (workspaceEnvVariables.containsKey("ENABLE_DYNAMIC_CREDENTIALS_AZURE")) {
            workspaceEnvVariables = dynamicCredentialsService.generateDynamicCredentialsAzure(job,
                    workspaceEnvVariables);
        }

        if (workspaceEnvVariables.containsKey("ENABLE_DYNAMIC_CREDENTIALS_VAULT")) {
            workspaceEnvVariables = dynamicCredentialsService.generateDynamicCredentialsVault(job,
                    workspaceEnvVariables);
        }

        if (workspaceEnvVariables.containsKey("ENABLE_DYNAMIC_CREDENTIALS_AWS")) {
            workspaceEnvVariables = dynamicCredentialsService.generateDynamicCredentialsAws(job, workspaceEnvVariables);
        }

        if (workspaceEnvVariables.containsKey("ENABLE_DYNAMIC_CREDENTIALS_GCP")) {
            workspaceEnvVariables = dynamicCredentialsService.generateDynamicCredentialsGcp(job, workspaceEnvVariables);
        }

        if (workspaceEnvVariables.containsKey("PRIVATE_EXTENSION_VCS_ID_AUTH")) {
            log.warn(
                    "Found PRIVATE_EXTENSION_VCS_ID_AUTH, adding authentication information for private extension repository");

            Optional<Vcs> vcs = vcsRepository
                    .findById(UUID.fromString(workspaceEnvVariables.get("PRIVATE_EXTENSION_VCS_ID_AUTH")));
            if (vcs.isPresent()) {
                workspaceEnvVariables.put("TERRAKUBE_PRIVATE_EXTENSION_REPO_TYPE", vcs.get().getVcsType().toString());
                workspaceEnvVariables.put("TERRAKUBE_PRIVATE_EXTENSION_REPO_TOKEN", vcs.get().getAccessToken());
                workspaceEnvVariables.put("TERRAKUBE_PRIVATE_EXTENSION_REPO_TOKEN_TYPE",
                        vcs.get().getConnectionType().toString());
            } else {
                log.error("VCS for private extension repository not found");
            }
        }

        return workspaceEnvVariables;
    }

    private HashMap<String, String> loadOtherTerraformVariables(Job job, Flow flow,
            HashMap<String, String> workspaceTerraformVariables) {
        if (flow.getInputsTerraform() != null
                || (flow.getImportComands() != null && flow.getImportComands().getInputsTerraform() != null)) {
            if (flow.getImportComands() != null && flow.getImportComands().getInputsTerraform() != null) {
                log.info("Loading TERRAFORM inputs from ImportComands");
                workspaceTerraformVariables = loadInputData(job, Category.TERRAFORM,
                        new HashMap(flow.getImportComands().getInputsTerraform()), workspaceTerraformVariables);
            }

            if (flow.getInputsTerraform() != null) {
                log.info("Loading TERRAFORM inputs from InputsTerraform");
                workspaceTerraformVariables = loadInputData(job, Category.TERRAFORM,
                        new HashMap(flow.getInputsTerraform()), workspaceTerraformVariables);
            }

        } else {
            log.info("Loading default env variables to job");
            workspaceTerraformVariables = loadDefault(job, Category.TERRAFORM, workspaceTerraformVariables);
        }
        return workspaceTerraformVariables;
    }

    private HashMap<String, String> loadInputData(Job job, Category categoryVar, HashMap<String, String> importFrom,
            HashMap<String, String> importTo) {
        Map<String, String> finalWorkspaceEnvVariables = importTo;
        importFrom.forEach((key, value) -> {
            java.lang.String searchValue = value.replace("$", "");
            Globalvar globalvar = globalVarRepository.getGlobalvarByOrganizationAndCategoryAndKey(job.getOrganization(),
                    categoryVar, searchValue);
            log.info("Searching globalvar {} ({}) in Org {} found {}", searchValue, categoryVar,
                    job.getOrganization().getName(), (globalvar != null) ? true : false);
            if (globalvar != null) {
                finalWorkspaceEnvVariables.putIfAbsent(key, globalvar.getValue());
            }
        });

        return new HashMap(finalWorkspaceEnvVariables);
    }

    private HashMap<String, String> loadDefault(Job job, Category category, HashMap<String, String> workspaceData) {
        for (Globalvar globalvar : globalVarRepository.findByOrganization(job.getOrganization())) {
            if (globalvar.getCategory().equals(category)) {
                workspaceData.putIfAbsent(globalvar.getKey(), globalvar.getValue());
                log.info("Adding {} Global Variable Key: {} Value {}", category, globalvar.getKey(),
                        globalvar.isSensitive() ? "sensitive" : globalvar.getValue());
            }
        }

        List<Reference> referenceList = referenceRepository.findByWorkspace(job.getWorkspace()).orElse(new ArrayList<>());

        List<Collection> collectionList = new ArrayList();
        for (Reference reference : referenceList) {
            collectionList.add(reference.getCollection());
        }

        List<Collection> sortedList = collectionList.stream()
                .sorted(Comparator.comparing(Collection::getPriority).reversed())
                .toList();

        sortedList.stream().forEach(collection -> {
            log.info("Adding data from collection {} using priority {}", collection.getName(), collection.getPriority());

            List<Item> itemList = new ArrayList();
            itemList = collection.getItem();
            for (Item item : itemList) {
                if (item.getCategory().equals(category)) {
                    workspaceData.putIfAbsent(item.getKey(), item.getValue());
                }
            }

        });


        return workspaceData;
    }

}
