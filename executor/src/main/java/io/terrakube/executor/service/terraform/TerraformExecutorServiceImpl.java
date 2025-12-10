package io.terrakube.executor.service.terraform;

import com.diogonunes.jcolor.AnsiFormat;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.executor.ExecutorJobResult;
import io.terrakube.executor.service.logs.LogsConsumer;
import io.terrakube.executor.service.logs.ProcessLogs;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.scripts.ScriptEngineService;
import io.terrakube.terraform.TerraformClient;
import io.terrakube.terraform.TerraformProcessData;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.text.TextStringBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.diogonunes.jcolor.Ansi.colorize;
import static com.diogonunes.jcolor.Attribute.*;
import static io.terrakube.executor.service.workspace.SetupWorkspaceImpl.SSH_DIRECTORY;

@Slf4j
@Service
public class TerraformExecutorServiceImpl implements TerraformExecutor {

    private static final String STEP_SEPARATOR = "***************************************";

    TerraformClient terraformClient;
    TerraformState terraformState;
    ScriptEngineService scriptEngineService;
    RedisTemplate redisTemplate;
    boolean enableColorOutput;
    ProcessLogs logsService;
    int redisTimeout;

    public TerraformExecutorServiceImpl(TerraformClient terraformClient, TerraformState terraformState, ScriptEngineService scriptEngineService, ProcessLogs logsService, @Value("${io.terrakube.terraform.flags.enableColor}") boolean enableColorOutput, RedisTemplate redisTemplate, @Value("${io.terrakube.executor.redis.timeout}") int redisTimeout) {
        this.terraformClient = terraformClient;
        this.terraformState = terraformState;
        this.scriptEngineService = scriptEngineService;
        this.redisTemplate = redisTemplate;
        this.logsService = logsService;
        this.enableColorOutput = enableColorOutput;
        this.redisTimeout = redisTimeout;
    }

    public File getTerraformWorkingDir(TerraformJob terraformJob, File workingDirectory) throws IOException {
        File terraformWorkingDir = workingDirectory;
        try {
            if (!terraformJob.getBranch().equals("remote-content") || (terraformJob.getFolder() != null && !terraformJob.getFolder().split(",")[0].equals("/"))) {
                terraformWorkingDir = new File(Path.of(workingDirectory.getCanonicalPath(), terraformJob.getFolder().split(",")[0]).toString());
                if (!terraformWorkingDir.isDirectory()) {
                    throw new IOException(String.format("Terraform Working Directory not exist: {}", terraformWorkingDir.getCanonicalPath()));
                }
            }
        } catch (IOException e) {
            log.error(e.getMessage());
        }
        log.info("Terraform Working Directory: {}", terraformWorkingDir.getCanonicalPath());
        return terraformWorkingDir;
    }

    private void waitForStreamCompletion(String jobId, int maxWaitSeconds) {
        int pollInterval = 1000; // 1 second
        int totalWait = 0;
        long lastMessageCount = -1;
        int stableCount = 0;

        while (totalWait < maxWaitSeconds * 1000) {
            try {
                // Check if there are pending messages in the stream
                Long streamLength = redisTemplate.opsForStream().size(jobId);

                if (streamLength != null) {
                    if (streamLength.equals(lastMessageCount)) {
                        stableCount++;
                        // If stream size hasn't changed for 3 consecutive checks, consider it complete
                        if (stableCount >= 3) {
                            log.info("Stream appears complete for job {}", jobId);
                            break;
                        }
                    } else {
                        stableCount = 0;
                        lastMessageCount = streamLength;
                    }
                }

                Thread.sleep(pollInterval);
                totalWait += pollInterval;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for stream completion", e);
                break;
            }
        }

        log.info("Waited {} ms for stream completion", totalWait);
    }


    @Override
    public ExecutorJobResult plan(TerraformJob terraformJob, File workingDirectory, boolean isDestroy) {
        logsService.setupConsumerGroups(terraformJob.getJobId());
        ExecutorJobResult result;

        TextStringBuilder jobOutput = new TextStringBuilder();
        TextStringBuilder jobErrorOutput = new TextStringBuilder();
        try {
            File terraformWorkingDir = getTerraformWorkingDir(terraformJob, workingDirectory);
            boolean executionPlan = false;
            int exitCode = 0;
            boolean scriptBeforeSuccessPlan;
            boolean scriptAfterSuccessPlan;

            Consumer<String> planOutput = LogsConsumer.builder()
                    .jobId(Integer.valueOf(terraformJob.getJobId()))
                    .terraformOutput(jobOutput)
                    .stepId(terraformJob.getStepId())
                    .processLogs(logsService)
                    .lineNumber(new AtomicInteger(0))
                    .build();

            terraformClient.setRedirectErrorStream(true);
            boolean scriptBeforeInitSuccess = executePreInitScripts(terraformJob, terraformWorkingDir, planOutput);
            executeTerraformInit(
                    terraformJob,
                    terraformWorkingDir,
                    planOutput,
                    null);

            scriptBeforeSuccessPlan = executePreOperationScripts(terraformJob, terraformWorkingDir, planOutput);

            showTerraformMessage(terraformJob, "PLAN", planOutput);

            if (scriptBeforeSuccessPlan) {
                if (isDestroy) {
                    log.warn("Executor running a plan to destroy resources...");
                    exitCode = terraformClient.planDestroyDetailExitCode(
                            getTerraformProcessData(terraformJob, terraformWorkingDir),
                            planOutput,
                            null).get();
                } else {
                    exitCode = terraformClient.planDetailExitCode(
                            getTerraformProcessData(terraformJob, terraformWorkingDir),
                            planOutput,
                            null).get();
                }
            }

            if (exitCode != 1 || terraformJob.isIgnoreError()) {
                executionPlan = true;
            }

            log.warn("Terraform plan Executed: {} Exit Code: {}", executionPlan, exitCode);

            scriptAfterSuccessPlan = executePostOperationScripts(terraformJob, terraformWorkingDir, planOutput, executionPlan);

            waitForStreamCompletion(terraformJob.getJobId(), 300);

            result = generateJobResult(scriptAfterSuccessPlan, jobOutput.toString(), jobErrorOutput.toString());
            result.setPlanFile(executionPlan ? terraformState.saveTerraformPlan(terraformJob.getOrganizationId(),
                    terraformJob.getWorkspaceId(), terraformJob.getJobId(), terraformJob.getStepId(), terraformWorkingDir)
                    : "");
            result.setPlan(true);
            result.setExitCode(exitCode);
        } catch (IOException | ExecutionException | InterruptedException exception) {
            result = setError(exception);
            result.setExitCode(1);
        }
        return result;
    }

    @Override
    public ExecutorJobResult apply(TerraformJob terraformJob, File workingDirectory) {
        logsService.setupConsumerGroups(terraformJob.getJobId());
        ExecutorJobResult result;

        TextStringBuilder terraformOutput = new TextStringBuilder();
        TextStringBuilder terraformErrorOutput = new TextStringBuilder();
        try {
            File terraformWorkingDir = getTerraformWorkingDir(terraformJob, workingDirectory);
            Consumer<String> applyOutput = LogsConsumer.builder()
                    .jobId(Integer.valueOf(terraformJob.getJobId()))
                    .lineNumber(new AtomicInteger(0))
                    .terraformOutput(terraformOutput)
                    .stepId(terraformJob.getStepId())
                    .processLogs(logsService)
                    .build();

            HashMap<String, String> terraformParameters = getWorkspaceParameters(terraformJob.getVariables());

            boolean execution = false;
            boolean scriptBeforeSuccess;
            boolean scriptAfterSuccess;
            terraformClient.setRedirectErrorStream(true);
            boolean scriptBeforeInitSuccess = executePreInitScripts(terraformJob, terraformWorkingDir, applyOutput);
            executeTerraformInit(
                    terraformJob,
                    terraformWorkingDir,
                    applyOutput,
                    null);

            scriptBeforeSuccess = executePreOperationScripts(terraformJob, terraformWorkingDir, applyOutput);

            showTerraformMessage(terraformJob, "APPLY", applyOutput);

            if (scriptBeforeSuccess) {
                TerraformProcessData terraformProcessData = getTerraformProcessData(terraformJob, terraformWorkingDir);
                terraformProcessData.setTerraformVariables((terraformState.downloadTerraformPlan(terraformJob.getOrganizationId(),
                        terraformJob.getWorkspaceId(), terraformJob.getJobId(), terraformJob.getStepId(),
                        terraformWorkingDir) ? new HashMap<>() : terraformParameters));
                execution = terraformClient.apply(
                        terraformProcessData,
                        applyOutput,
                        null).get();

                handleTerraformStateChange(terraformJob, terraformWorkingDir);

            }

            log.warn("Terraform apply Executed Successfully: {}", execution);
            scriptAfterSuccess = executePostOperationScripts(terraformJob, terraformWorkingDir, applyOutput, execution || terraformJob.isIgnoreError());

            waitForStreamCompletion(terraformJob.getJobId(), 300);
            result = generateJobResult(scriptAfterSuccess, terraformOutput.toString(), terraformErrorOutput.toString());
        } catch (IOException | ExecutionException | InterruptedException exception) {
            result = setError(exception);
        }
        return result;
    }

    @Override
    public ExecutorJobResult destroy(TerraformJob terraformJob, File workingDirectory) {
        logsService.setupConsumerGroups(terraformJob.getJobId());
        ExecutorJobResult result;

        TextStringBuilder jobOutput = new TextStringBuilder();
        TextStringBuilder jobErrorOutput = new TextStringBuilder();
        try {
            File terraformWorkingDir = getTerraformWorkingDir(terraformJob, workingDirectory);
            Consumer<String> outputDestroy = LogsConsumer.builder()
                    .jobId(Integer.valueOf(terraformJob.getJobId()))
                    .terraformOutput(jobOutput)
                    .stepId(terraformJob.getStepId())
                    .processLogs(logsService)
                    .lineNumber(new AtomicInteger(0))
                    .build();

            boolean execution = false;
            boolean scriptBeforeSuccess;
            boolean scriptAfterSuccess;
            terraformClient.setRedirectErrorStream(true);
            boolean scriptBeforeInitSuccess = executePreInitScripts(terraformJob, terraformWorkingDir, outputDestroy);
            executeTerraformInit(
                    terraformJob,
                    terraformWorkingDir,
                    outputDestroy,
                    null);

            scriptBeforeSuccess = executePreOperationScripts(terraformJob, terraformWorkingDir, outputDestroy);

            showTerraformMessage(terraformJob, "DESTROY", outputDestroy);

            if (scriptBeforeSuccess) {
                execution = terraformClient.destroy(
                        getTerraformProcessData(terraformJob, terraformWorkingDir),
                        outputDestroy,
                        null).get();

                handleTerraformStateChange(terraformJob, terraformWorkingDir);
            }

            log.warn("Terraform destroy Executed Successfully: {}", execution);
            scriptAfterSuccess = executePostOperationScripts(terraformJob, terraformWorkingDir, outputDestroy, execution);

            waitForStreamCompletion(terraformJob.getJobId(), 300);
            result = generateJobResult(scriptAfterSuccess, jobOutput.toString(), jobErrorOutput.toString());
        } catch (IOException | ExecutionException | InterruptedException exception) {
            result = setError(exception);
        }
        return result;
    }

    private ExecutorJobResult generateJobResult(boolean scriptAfterSuccess, String jobOutput, String jobErrorOutput) {
        ExecutorJobResult jobResult = new ExecutorJobResult();
        jobResult.setSuccessfulExecution(scriptAfterSuccess);
        jobResult.setOutputLog(jobOutput);
        jobResult.setOutputErrorLog(jobErrorOutput);

        return jobResult;
    }

    private boolean executePreOperationScripts(TerraformJob terraformJob, File workingDirectory, Consumer<String> output) {
        boolean scriptBeforeSuccess;
        if (terraformJob.getCommandList() != null) {
            scriptBeforeSuccess = scriptEngineService.execute(
                    terraformJob,
                    terraformJob
                            .getCommandList()
                            .stream()
                            .filter(command -> command.isBefore() && !command.isBeforeInit())
                            .collect(Collectors.toCollection(LinkedList::new)),
                    workingDirectory,
                    output);
        } else {
            log.warn("No commands to run before terraform operation Job {}", terraformJob.getJobId());
            scriptBeforeSuccess = true;
        }
        return scriptBeforeSuccess;
    }

    private boolean executePreInitScripts(TerraformJob terraformJob, File workingDirectory, Consumer<String> output) {
        boolean scriptBeforeInitSuccess;
        if (terraformJob.getCommandList() != null) {
            scriptBeforeInitSuccess = scriptEngineService.execute(
                    terraformJob,
                    terraformJob
                            .getCommandList()
                            .stream()
                            .filter(command -> command.isBeforeInit())
                            .collect(Collectors.toCollection(LinkedList::new)),
                    workingDirectory,
                    output);
        } else {
            log.warn("No commands to run before terraform init Job {}", terraformJob.getJobId());
            scriptBeforeInitSuccess = true;
        }
        return scriptBeforeInitSuccess;
    }

    private boolean executePostOperationScripts(TerraformJob terraformJob, File workingDirectory, Consumer<String> output, boolean execution) {
        boolean scriptAfterSuccess;
        if (execution) {
            if (terraformJob.getCommandList() != null) {
                scriptAfterSuccess = scriptEngineService.execute(
                        terraformJob,
                        terraformJob
                                .getCommandList()
                                .stream()
                                .filter(command -> command.isAfter())
                                .collect(Collectors.toCollection(LinkedList::new)),
                        workingDirectory,
                        output);
            } else {
                scriptAfterSuccess = true;
            }
        } else {
            scriptAfterSuccess = false;
        }

        log.warn("No commands to run after terraform operation Job {}", scriptAfterSuccess);
        return scriptAfterSuccess;
    }

    private void handleTerraformStateChange(TerraformJob terraformJob, File workingDirectory)
            throws IOException, ExecutionException, InterruptedException {
        log.info("Running Terraform show");
        TextStringBuilder jsonState = new TextStringBuilder();
        TextStringBuilder rawTfState = new TextStringBuilder();
        Consumer<String> applyJSON = getStringConsumer(jsonState);
        Consumer<String> rawStateJSON = getStringConsumer(rawTfState);
        TerraformProcessData terraformProcessData = getTerraformProcessData(terraformJob, workingDirectory);
        terraformProcessData.setTerraformVariables(new HashMap());
        terraformProcessData.setTerraformEnvironmentVariables(new HashMap());
        Boolean showJsonState = terraformClient.show(terraformProcessData, applyJSON, applyJSON).get();
        Boolean showRawState = terraformClient.statePull(terraformProcessData, rawStateJSON, rawStateJSON).get();

        Thread.sleep(5000);

        if (Boolean.TRUE.equals(showRawState)) {
            terraformJob.setRawState(rawStateJSON.toString());
        }

        if (Boolean.TRUE.equals(showJsonState)) {
            log.info("Uploading terraform state json");
            terraformState.saveStateJson(terraformJob, jsonState.toString(), rawTfState.toString());

            TextStringBuilder jsonOutput = new TextStringBuilder();
            Consumer<String> terraformJsonOutput = getStringConsumer(jsonOutput);

            log.info("Checking terraform output json");
            Boolean showOutput = terraformClient.output(terraformProcessData, terraformJsonOutput, terraformJsonOutput).get();
            if (Boolean.TRUE.equals(showOutput)) {
                terraformJob.setTerraformOutput(jsonOutput.toString());
            }

        }
    }

    @Override
    public String version() {
        String terraformVersion = "";
        TextStringBuilder terraformOutput = new TextStringBuilder();
        TextStringBuilder terraformErrorOutput = new TextStringBuilder();
        try {
            terraformClient.setOutputListener(response -> {
                terraformOutput.appendln(response);
            });
            terraformClient.setErrorListener(response -> {
                terraformErrorOutput.appendln(response);
            });
            terraformVersion = terraformClient.version().get();
        } catch (IOException | ExecutionException | InterruptedException exception) {
            setError(exception);
        }
        return terraformVersion;
    }

    private ExecutorJobResult setError(Exception exception) {
        ExecutorJobResult error = generateJobResult(false, "", exception.getMessage());
        log.error(exception.getMessage());

        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return error;
    }

    private String executeTerraformInit(TerraformJob terraformJob, File workingDirectory, Consumer<String> output,
                                        Consumer<String> errorOutput) throws IOException, ExecutionException, InterruptedException {
        if (terraformJob.isShowHeader()) {
            initBanner(terraformJob, output);
        }

        TerraformProcessData terraformProcessData = getTerraformProcessData(terraformJob, workingDirectory);
        terraformProcessData.setTerraformEnvironmentVariables(terraformProcessData.getTerraformEnvironmentVariables());
        terraformProcessData.setTerraformVariables(new HashMap<>());

        if (terraformJob.isShowHeader()) {
            terraformClient.init(terraformProcessData, output, errorOutput).get();
        } else {
            terraformClient.init(terraformProcessData, s -> {
                log.info(s);
            }, s -> {
                log.info(s);
            }).get();
        }

        Thread.sleep(5000);
        return terraformProcessData.getTerraformBackendConfigFileName();
    }

    private HashMap<String, String> getWorkspaceParameters(HashMap<String, String> parameters) {
        return parameters != null ? parameters : new HashMap<>();
    }

    private Consumer<String> getStringConsumer(TextStringBuilder terraformOutput) {
        return responseOutput -> {
            log.info(responseOutput);
            terraformOutput.appendln(responseOutput);
        };
    }

    private void initBanner(TerraformJob terraformJob, Consumer<String> output) {
        AnsiFormat colorMessage = enableColorOutput ? new AnsiFormat(GREEN_TEXT(), BLACK_BACK(), BOLD()) : new AnsiFormat(WHITE_TEXT(), BLACK_BACK(), BOLD());
        output.accept(colorize(STEP_SEPARATOR, colorMessage));
        output.accept(
                colorize("Initializing Terrakube Job " + terraformJob.getJobId() + " Step " + terraformJob.getStepId(),
                        colorMessage));
        output.accept(colorize(String.format("Running %s ", getIaCType(terraformJob)) + terraformJob.getTerraformVersion(), colorMessage));
        output.accept(colorize("\n\n" + STEP_SEPARATOR, colorMessage));
        output.accept(colorize(String.format("Running %s Init: ", getIaCType(terraformJob)), colorMessage));
    }

    private String getIaCType(TerraformJob terraformJob) {
        return terraformJob.isTofu() ? "Tofu" : "Terraform";
    }

    private void showTerraformMessage(TerraformJob terraformJob, String operation, Consumer<String> output) throws InterruptedException {
        AnsiFormat colorMessage = enableColorOutput ? new AnsiFormat(GREEN_TEXT(), BLACK_BACK(), BOLD()) : new AnsiFormat(WHITE_TEXT(), BLACK_BACK(), BOLD());
        output.accept(colorize(STEP_SEPARATOR, colorMessage));
        output.accept(colorize(String.format("Running %s ", getIaCType(terraformJob)) + operation, colorMessage));
        output.accept(colorize(STEP_SEPARATOR, colorMessage));
        Thread.sleep(2000);
    }

    private TerraformProcessData getTerraformProcessData(TerraformJob terraformJob, File workingDirectory) {

        terraformState.getBackendStateFile(terraformJob.getOrganizationId(),
                terraformJob.getWorkspaceId(), workingDirectory, terraformJob.getTerraformVersion());

        File sshKeyFile = null;
        if (terraformJob.getVcsType().startsWith("SSH") && terraformJob.getModuleSshKey() != null && !terraformJob.getModuleSshKey().isEmpty()) {
            //USING MODULE SSH KEY TO DOWNLOAD THE MODULES AND NOT THE DEFAULT SSH KEY THAT WAS USED TO CLONE THE WORKSPACE
            String sshFilePath = String.format(SSH_DIRECTORY, FileUtils.getUserDirectoryPath(), terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(), terraformJob.getJobId());
            log.warn("1 - Using SSH key from: {}", sshFilePath);
            sshKeyFile = new File(sshFilePath);
        } else if (terraformJob.getVcsType().startsWith("SSH")) {
            //USING THE SAME SSH KEY THAT WAS USED TO CLONE THE REPOSITORY
            String sshFileName = terraformJob.getVcsType().split("~")[1];
            String sshFilePath = String.format(SSH_DIRECTORY, FileUtils.getUserDirectoryPath(), terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(), sshFileName);
            log.warn("2 - Using SSH key from: {}", sshFilePath);
            sshKeyFile = new File(sshFilePath);
        } else if (terraformJob.getModuleSshKey() != null && !terraformJob.getModuleSshKey().isEmpty()) {
            //USING MODULE SSH KEY TO DOWNLOAD THE MODULES IN OTHER CASE FOR EXAMPLE WHEN USING VCS WITH A MODULE SSH KEY
            String sshFilePath = String.format(SSH_DIRECTORY, FileUtils.getUserDirectoryPath(), terraformJob.getOrganizationId(), terraformJob.getWorkspaceId(), terraformJob.getJobId());
            log.warn("3 - Using SSH key from: {}", sshFilePath);
            sshKeyFile = new File(sshFilePath);
        } else {
            log.warn("Not using any SSH key to download modules");
        }

        // Process variables - separate HCL and non-HCL variables
        HashMap<String, String> nonHclVariables = processVariables(terraformJob.getVariables(), workingDirectory);

        return TerraformProcessData.builder()
                .terraformVersion(terraformJob.getTerraformVersion())
                .terraformVariables(nonHclVariables)
                .terraformEnvironmentVariables(loadTempEnvironmentVariables(workingDirectory, terraformJob))
                .workingDirectory(workingDirectory)
                .refresh(terraformJob.isRefresh())
                .refreshOnly(terraformJob.isRefreshOnly())
                .tofu(terraformJob.isTofu())
                .sshFile(sshKeyFile)
                .build();
    }

    private HashMap<String, String> processVariables(List<io.terrakube.executor.service.mode.TerraformVariable> variables, File workingDirectory) {
        HashMap<String, String> nonHclVariables = new HashMap<>();
        List<io.terrakube.executor.service.mode.TerraformVariable> hclVariables = new ArrayList<>();

        // Separate HCL and non-HCL variables
        for (io.terrakube.executor.service.mode.TerraformVariable variable : variables) {
            if (variable.isHcl()) {
                hclVariables.add(variable);
                log.info("Found HCL variable: {}", variable.getKey());
            } else {
                nonHclVariables.put(variable.getKey(), variable.getValue());
            }
        }

        // Write HCL variables to .auto.tfvars file
        if (!hclVariables.isEmpty()) {
            writeHclVariablesToFile(hclVariables, workingDirectory);
        }

        return nonHclVariables;
    }

    private void writeHclVariablesToFile(List<io.terrakube.executor.service.mode.TerraformVariable> hclVariables, File workingDirectory) {
        try {
            File tfvarsFile = new File(workingDirectory, "terrakube.auto.tfvars");
            StringBuilder tfvarsContent = new StringBuilder();
            tfvarsContent.append("# Terrakube HCL Variables - Auto-generated\n");
            tfvarsContent.append("# This file is managed by Terrakube and should not be edited manually\n\n");

            for (io.terrakube.executor.service.mode.TerraformVariable variable : hclVariables) {
                tfvarsContent.append(variable.getKey())
                        .append(" = ")
                        .append(variable.getValue())
                        .append("\n");
            }

            Files.write(tfvarsFile.toPath(), tfvarsContent.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            log.info("Created terrakube.auto.tfvars with {} HCL variables at: {}", hclVariables.size(), tfvarsFile.getAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to write HCL variables to .auto.tfvars file", e);
        }
    }

    private HashMap<String, String> loadTempEnvironmentVariables(File workingDirectory, TerraformJob terraformJob) {
        String workingEnvTemp = workingDirectory.getAbsolutePath() + "/.terrakube_temp_env";
        Path pathEnv = Paths.get(workingEnvTemp);
        if (Files.exists(pathEnv)) {
            log.info("File .terrakube_env exists, loading environment variables to terraform/tofu process");
            try (BufferedReader reader = Files.newBufferedReader(pathEnv)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] split = line.split("=");
                    log.info("Loading {}", split[0]);
                    terraformJob.getEnvironmentVariables().put(split[0], split[1]);
                }
            } catch (IOException e) {
                log.error("Error reading file: {}", e.getMessage());
            }
        } else {
            log.info("File terrakube_env does not exist");
        }

        return terraformJob.getEnvironmentVariables();
    }
}
