package io.terrakube.api.plugin.scheduler.job.tcl.executor;

import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.terrakube.api.plugin.scheduler.job.tcl.executor.ephemeral.EphemeralExecutorService;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.persistent.PersistentExecutorService;
import io.terrakube.api.plugin.scheduler.job.tcl.model.Flow;
import io.terrakube.api.plugin.token.dynamic.DynamicCredentialsService;
import io.terrakube.api.plugin.vcs.TokenService;
import io.terrakube.api.repository.GlobalVarRepository;
import io.terrakube.api.repository.ReferenceRepository;
import io.terrakube.api.repository.SshRepository;
import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.rs.collection.Collection;
import io.terrakube.api.rs.collection.Reference;
import io.terrakube.api.rs.collection.item.Item;
import io.terrakube.api.rs.globalvar.Globalvar;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.address.Address;
import io.terrakube.api.rs.job.address.AddressType;
import io.terrakube.api.rs.ssh.Ssh;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsConnectionType;
import io.terrakube.api.rs.workspace.parameters.Category;
import io.terrakube.api.rs.workspace.parameters.Variable;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ExecutorService {

    @Value("${io.terrakube.hostname}")
    String hostname;

    @Value("${io.terrakube.tools.repository}")
    String toolsRepository;

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
    PersistentExecutorService persistentExecutorService;

    @Autowired
    TokenService tokenService;
    @Autowired
    private VariableRepository variableRepository;
    @Autowired
    private ReferenceRepository referenceRepository;

    public void execute(Job job, String stepId, Flow flow) throws ExecutionException {
        log.info("Pending Job: {} WorkspaceId: {}", job.getId(), job.getWorkspace().getId());

        ExecutorContext executorContext = ExecutorContext.builder().build();
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

        List<TerraformVariable> terraformVariables = new ArrayList<>();
        HashMap<String, String> environmentVariables = new HashMap<>();
        environmentVariables.put("TF_IN_AUTOMATION", "1");
        environmentVariables.put("workspaceName", job.getWorkspace().getName());
        environmentVariables.put("organizationName", job.getOrganization().getName());
        List<Variable> variableList = variableRepository.findByWorkspace(job.getWorkspace()).orElse(new ArrayList<>());
        for (Variable variable : variableList) {
            if (variable.getCategory().equals(Category.TERRAFORM)) {
                log.info("Adding terraform variable: {} (HCL: {})", variable.getKey(), variable.isHcl());
                terraformVariables.add(new TerraformVariable(variable.getKey(), variable.getValue(), variable.isHcl()));
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
        executorContext = validateJobAddress(executorContext, job);
        if (executorContext.getEnvironmentVariables().containsKey("TERRAKUBE_ENABLE_EPHEMERAL_EXECUTOR")) {
            ephemeralExecutorService.send(job, executorContext);
        } else {
            persistentExecutorService.send(job, executorContext);
        }
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

    private boolean iacType(Job job) {
        return job.getWorkspace().getIacType() != null && job.getWorkspace().getIacType().equals("terraform") ? false
                : true;
    }

    private HashMap<String, String> loadOtherEnvironmentVariables(Job job, Flow flow,
            HashMap<String, String> workspaceEnvVariables) {
        if (flow.getInputsEnv() != null
                || (flow.getImportCommands() != null && flow.getImportCommands().getInputsEnv() != null)) {
            if (flow.getImportCommands() != null && flow.getImportCommands().getInputsEnv() != null) {
                log.info("Loading ENV inputs from ImportComands");
                workspaceEnvVariables = loadInputData(job, Category.ENV,
                        new HashMap(flow.getImportCommands().getInputsEnv()), workspaceEnvVariables);
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
                workspaceEnvVariables.put("TERRAKUBE_PRIVATE_EXTENSION_REPO_TOKEN_TYPE",
                        vcs.get().getConnectionType().toString());

                if (vcs.get().getConnectionType() == VcsConnectionType.OAUTH) {
                    workspaceEnvVariables.put("TERRAKUBE_PRIVATE_EXTENSION_REPO_TOKEN", vcs.get().getAccessToken());
                } else {
                    if (toolsRepository == null || toolsRepository.isEmpty()) {
                        log.error(
                                "Missing tools repository configuration while configuring private extensions for job {} on workspace {}",
                                job.getId(), job.getWorkspace().getName());
                    } else {
                        try {
                            String accessToken = tokenService.getAccessToken(toolsRepository, vcs.get());
                            workspaceEnvVariables.put("TERRAKUBE_PRIVATE_EXTENSION_REPO_TOKEN", accessToken);
                        } catch (JsonProcessingException | NoSuchAlgorithmException | InvalidKeySpecException
                                | URISyntaxException e) {
                            log.error(
                                    "Failed to fetch access token for private extension repository for job {} on workspace {}, error {}",
                                    job.getId(), job.getWorkspace().getName(), e);
                        }
                    }
                }
            } else {
                log.error("VCS for private extension repository not found");
            }
        }

        return workspaceEnvVariables;
    }

    private List<TerraformVariable> loadOtherTerraformVariables(Job job, Flow flow,
            List<TerraformVariable> workspaceTerraformVariables) {
        if (flow.getInputsTerraform() != null
                || (flow.getImportCommands() != null && flow.getImportCommands().getInputsTerraform() != null)) {
            if (flow.getImportCommands() != null && flow.getImportCommands().getInputsTerraform() != null) {
                log.info("Loading TERRAFORM inputs from ImportComands");
                workspaceTerraformVariables = loadInputDataForTerraformVariables(job, Category.TERRAFORM,
                        new HashMap(flow.getImportCommands().getInputsTerraform()), workspaceTerraformVariables);
            }

            if (flow.getInputsTerraform() != null) {
                log.info("Loading TERRAFORM inputs from InputsTerraform");
                workspaceTerraformVariables = loadInputDataForTerraformVariables(job, Category.TERRAFORM,
                        new HashMap(flow.getInputsTerraform()), workspaceTerraformVariables);
            }

        } else {
            log.info("Loading default env variables to job");
            workspaceTerraformVariables = loadDefaultTerraformVariables(job, workspaceTerraformVariables);
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

    private List<TerraformVariable> loadDefaultTerraformVariables(Job job, List<TerraformVariable> workspaceData) {
        // Get existing variable keys for deduplication
        Set<String> existingKeys = workspaceData.stream()
                .map(TerraformVariable::getKey)
                .collect(java.util.stream.Collectors.toSet());

        // Load global variables
        for (Globalvar globalvar : globalVarRepository.findByOrganization(job.getOrganization())) {
            if (globalvar.getCategory().equals(Category.TERRAFORM) && !existingKeys.contains(globalvar.getKey())) {
                workspaceData.add(new TerraformVariable(globalvar.getKey(), globalvar.getValue(), globalvar.isHcl()));
                existingKeys.add(globalvar.getKey());
                log.info("Adding TERRAFORM Global Variable Key: {} Value {} HCL: {}", globalvar.getKey(),
                        globalvar.isSensitive() ? "sensitive" : globalvar.getValue(), globalvar.isHcl());
            }
        }

        // Load collection items
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
            List<Item> itemList = collection.getItem();
            for (Item item : itemList) {
                if (item.getCategory().equals(Category.TERRAFORM) && !existingKeys.contains(item.getKey())) {
                    workspaceData.add(new TerraformVariable(item.getKey(), item.getValue(), item.isHcl()));
                    existingKeys.add(item.getKey());
                    log.info("Adding TERRAFORM Collection Item Key: {} Value {} HCL: {}", item.getKey(),
                            item.getValue(), item.isHcl());
                }
            }
        });

        return workspaceData;
    }

    private List<TerraformVariable> loadInputDataForTerraformVariables(Job job, Category categoryVar,
            HashMap<String, String> importFrom, List<TerraformVariable> importTo) {
        // Get existing variable keys for deduplication
        Set<String> existingKeys = importTo.stream()
                .map(TerraformVariable::getKey)
                .collect(java.util.stream.Collectors.toSet());

        importFrom.forEach((key, value) -> {
            if (!existingKeys.contains(key)) {
                java.lang.String searchValue = value.replace("$", "");
                Globalvar globalvar = globalVarRepository.getGlobalvarByOrganizationAndCategoryAndKey(
                        job.getOrganization(), categoryVar, searchValue);
                log.info("Searching globalvar {} ({}) in Org {} found {}", searchValue, categoryVar,
                        job.getOrganization().getName(), (globalvar != null));
                if (globalvar != null) {
                    importTo.add(new TerraformVariable(key, globalvar.getValue(), globalvar.isHcl()));
                    existingKeys.add(key);
                }
            }
        });

        return importTo;
    }

}
