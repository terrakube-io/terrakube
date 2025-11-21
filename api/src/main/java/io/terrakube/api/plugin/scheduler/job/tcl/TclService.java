package io.terrakube.api.plugin.scheduler.job.tcl;

import io.terrakube.api.plugin.scheduler.job.tcl.model.*;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.TemplateRepository;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsConnectionType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;

@AllArgsConstructor
@Service
@Getter
@Setter
@Slf4j
public class TclService {

    private static final String IMPORT_DIRECTORY = "%s/.terraform-spring-boot/importCommands/%s";

    JobRepository jobRepository;
    StepRepository stepRepository;
    TemplateRepository templateRepository;
    VcsRepository vcsRepository;

    @Transactional
    public Job initJobConfiguration(Job job) {
        log.info("InitialJobSetup {}", job.getId());
        if (job.getStep().isEmpty()) {

            FlowConfig flowConfig = null;
            if (job.getTemplateReference() != null) {
                String tcl = getTemplateTcl(job.getTemplateReference());
                job.setTcl(tcl);
                jobRepository.save(job);
                flowConfig = getFlowConfig(tcl);
            } else {
                flowConfig = getFlowConfig(job.getTcl());
            }
            log.info("Custom Job Setup: \n {}", flowConfig.toString());

            flowConfig.getFlow().parallelStream().forEach(flow -> {
                log.info("Creating step: {}", flow.toString());
                Step newStep = new Step();
                newStep.setStatus(JobStatus.pending);
                newStep.setStepNumber(flow.getStep());
                if (flow.getName() != null) {
                    newStep.setName(flow.getName());
                } else {
                    newStep.setName("Running Step" + flow.getStep());
                }
                log.info("Step name {}", newStep.getName());
                Job parentJob = jobRepository.getReferenceById(job.getId());
                newStep.setJob(parentJob);
                newStep = stepRepository.save(newStep);
                log.info("Parent {} Step created {}", newStep.getJob().getId(), newStep.getId());
            });
            return jobRepository.getReferenceById(job.getId());
        } else
            return job;
    }

    private FlowConfig getFlowConfig(String tcl) {
        Yaml yaml = new Yaml(new Constructor(FlowConfig.class, new LoaderOptions()));
        FlowConfig flowConfig = null;
        try {
            FlowConfig temp = yaml.load(new String(Base64.getDecoder().decode(tcl)));
            log.info("FlowConfig: \n {}", temp);
            flowConfig = yaml.load(new String(Base64.getDecoder().decode(tcl)));

            if (flowConfig.getFlow().isEmpty()) {
                log.error("Exception parsing yaml: template with no flows");
                return setErrorFlowYaml("Yaml Template does not have any flow");
            }
        } catch (Exception ex) {
            log.error("Exception parsing yaml: {}", ex.getMessage());
            flowConfig = setErrorFlowYaml(ex.getMessage());
        }
        return flowConfig;
    }

    private FlowConfig setErrorFlowYaml(String message) {
        FlowConfig flowConfig = new FlowConfig();
        List<Flow> flowList = new ArrayList();
        Flow errorFlow = new Flow();
        errorFlow.setType(FlowType.yamlError.toString());
        errorFlow.setStep(100);
        errorFlow.setError(message);
        errorFlow.setName("Template Yaml Error, check API logs");
        flowList.add(errorFlow);
        flowConfig.setFlow(flowList);

        return flowConfig;
    }

    public Flow getNextFlow(Job job) {
        TreeMap<Integer, Step> map = getPendingSteps(job);

        if (!map.isEmpty()) {
            log.info("Next Command: {}", map.firstKey());

            Optional<Flow> nextFlow = getFlowConfig(job.getTcl())
                    .getFlow()
                    .stream()
                    .filter(flow -> flow.getStep() == map.firstKey())
                    .findFirst();

            if (nextFlow.isPresent()) {
                Flow finalFlow = nextFlow.get();
                log.info("Checking import commands in YAML");
                ImportCommands importCommands = finalFlow.getImportCommands();
                if (importCommands != null) {
                    log.info("Import commands from {} branch {} folder {} VcsId {}", importCommands.getRepository(), importCommands.getBranch(), importCommands.getFolder().split(",")[0], importCommands.getVcsId());
                    finalFlow.setCommands(importCommands(importCommands.getRepository(), importCommands.getBranch(), importCommands.getFolder().split(",")[0], importCommands.getVcsId()));
                }

                return finalFlow;

            } else {
                return null;
            }
        } else
            return null;
    }

    private List<Command> importCommands(String repository, String branch, String folder, String vcsId) {
        List<Command> commands = new ArrayList<>();
        try {
            File importFolder = generateImportFolder();
            log.info("Get commands text");
            String commandsText = getCommandList(repository, branch, folder, generateImportFolder(), vcsId);

            log.info("Parsing yaml file");
            Yaml yaml = new Yaml(new Constructor(CommandConfig.class, new LoaderOptions()));
            CommandConfig temp = yaml.load(commandsText);

            commands = temp.getCommands();

            log.info("Importing commands \n{}\n", commandsText);

            log.info("Cleaning temp directory");
            FileUtils.cleanDirectory(importFolder);
        } catch (IOException e) {
            log.error(e.getMessage());
        }
        return commands;
    }

    private String getCommandList(String repository, String branch, String folder, File folderImport, String vcsId) {
        String commandList = "";
        try {

            log.info("Cloning template import repository");
            if(vcsId !=null && !vcsId.isEmpty()) {
                Git.cloneRepository()
                        .setURI(repository)
                        .setDirectory(folderImport)
                        .setBranch(branch)
                        .setCredentialsProvider(setupImportCredentials(vcsId))
                        .call();
            } else {
                log.info("Importing from public repository");
                Git.cloneRepository()
                        .setURI(repository)
                        .setDirectory(folderImport)
                        .setBranch(branch)
                        .call();
            }
            File importData = null;
            if (folder.equals("/")) {
                importData = new File(String.format("%s/commands.yaml", folderImport.getCanonicalPath()));
            } else {
                importData = new File(String.format("%s/%s/commands.yaml", folderImport.getCanonicalPath(), folder));
            }

            log.info("Reading commands.yaml file");
            commandList = FileUtils.readFileToString(importData, Charset.defaultCharset());
        } catch (IOException | GitAPIException e) {
            log.error(e.getMessage());
        }
        return commandList;
    }

    private CredentialsProvider setupImportCredentials(String vcsId) {
        CredentialsProvider importCredentialProvider = null;
        Vcs vcs = vcsRepository.findById(UUID.fromString(vcsId)).orElse(null);
        assert vcs != null;
        log.info("Import using VCS id: {}", vcs.getId());
        switch (vcs.getVcsType().toString()) {
            case "GITHUB":
                if (vcs.getConnectionType().equals(VcsConnectionType.OAUTH)) {
                    importCredentialProvider = new UsernamePasswordCredentialsProvider(vcs.getAccessToken(), "");
                } else {
                    importCredentialProvider = new UsernamePasswordCredentialsProvider("x-access-token", vcs.getAccessToken());
                }
                break;
            case "AZURE_DEVOPS", "AZURE_SP_MI":
                importCredentialProvider = new UsernamePasswordCredentialsProvider("dummy", vcs.getAccessToken());
                break;
            case "GITLAB":
                importCredentialProvider = new UsernamePasswordCredentialsProvider("oauth2", vcs.getAccessToken());
                break;
            case "BITBUCKET":
                importCredentialProvider = new UsernamePasswordCredentialsProvider("x-token-auth", vcs.getAccessToken());
                break;
            default:
                importCredentialProvider = null;
                break;
        }
        return importCredentialProvider;
    }

    private File generateImportFolder() {
        log.info("Creating import folder");
        String importCommandFolder = String.format(IMPORT_DIRECTORY, FileUtils.getUserDirectoryPath(), UUID.randomUUID());
        File importFolder = new File(importCommandFolder);
        try {
            if (!importFolder.exists()) {
                log.info("Creating new import folder for {}", importCommandFolder);
                FileUtils.forceMkdir(importFolder);
            } else {
                FileUtils.cleanDirectory(importFolder);
            }
        } catch (IOException e) {
            log.error(e.getMessage());
        }
        return importFolder;
    }

    private TreeMap<Integer, Step> getPendingSteps(Job job) {
        final TreeMap<Integer, Step> map = new TreeMap<>();
        stepRepository.findByJobId(job.getId())
                .stream()
                .filter(step -> step.getStatus().equals(JobStatus.pending))
                .forEach(step -> map.put(Integer.valueOf(step.getStepNumber()), step));
        log.info("Pending steps {}", map.size());
        return map;
    }

    public String getCurrentStepId(Job job) {
        return getPendingSteps(job).firstEntry().getValue().getId().toString();
    }

    private String getTemplateTcl(String templateId) {
        return templateRepository.getReferenceById(UUID.fromString(templateId)).getTcl();
    }
}
