package io.terrakube.executor.service.scripts.bash;

import com.diogonunes.jcolor.AnsiFormat;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.scripts.CommandExecution;
import io.terrakube.executor.service.scripts.ScriptEngineService;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.NotFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.diogonunes.jcolor.Ansi.colorize;
import static com.diogonunes.jcolor.Attribute.*;

@Slf4j
@Service
public class BashEngine implements CommandExecution {
    private static final String USER_BASH_SCRIPT = "/userScript.sh";
    private static final String TERRAFORM_DIRECTORY="/.terraform-spring-boot/terraform/";
    private static final String TOFU_DIRECTORY="/.terraform-spring-boot/tofu/";

    private final ExecutorService executor = Executors.newWorkStealingPool();

    private String terrakubeApi;

    private WorkspaceSecurity workspaceSecurity;

    public BashEngine(WorkspaceSecurity workspaceSecurity, @Value("${io.terrakube.api.url}") String terrakubeApi) {
        this.terrakubeApi = terrakubeApi;
        this.workspaceSecurity = workspaceSecurity;
    }

    @Override
    public boolean execute(TerraformJob terraformJob, String script, File terraformWorkingDir, Consumer<String> output) {
        boolean executeSuccess = true;
        File bashScript = new File(
                FilenameUtils.separatorsToSystem(
                        terraformWorkingDir.getAbsolutePath().concat(USER_BASH_SCRIPT)
                )
        );

        try {
            log.info("ScriptPath: {}", bashScript.toURI().toURL());
            FileUtils.writeStringToFile(bashScript, script, Charset.defaultCharset());

            ProcessLauncher processLauncher = setupBashProcess(terraformJob, terraformWorkingDir, bashScript, output, output);
            Integer exitCode = processLauncher.launch().get();
            log.info("Exit code {}", exitCode);
            if(exitCode != 0) {
                log.error("Script Exit Code {} \n Script \n {}", terraformJob.getJobId(), script);
                AnsiFormat colorError = new AnsiFormat(RED_TEXT(), BLACK_BACK(), BOLD());
                output.accept(colorize("Script Exit Code ==>" + exitCode, colorError));
                executeSuccess = false;
            }
        } catch (IOException e) {
            log.error(e.getMessage());
            executeSuccess = false;
            output.accept(e.getMessage());
        } catch (InterruptedException | ExecutionException e) {
            log.error(e.getMessage());
            executeSuccess = false;
            output.accept(e.getMessage());
            Thread.currentThread().interrupt();
        }

        return executeSuccess;
    }

    private ProcessLauncher setupBashProcess(TerraformJob terraformJob, File workingDirectory, File bashScript, Consumer<String> outputListener, Consumer<String> errorListener) {
        ProcessLauncher processLauncher = new ProcessLauncher(this.executor, "bash", bashScript.getAbsolutePath());
        processLauncher.setDirectory(workingDirectory);

        String tempEnv = workingDirectory.getAbsolutePath() + "/.terrakube_temp_env";
        Path path = Paths.get(tempEnv);
        if (Files.exists(path)) {
            log.info("File .terrakube_env exists");
            try (BufferedReader reader = Files.newBufferedReader(path)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] split = line.split("=");
                    log.info("Loading {}", split[0]);
                    processLauncher.setEnvironmentVariable(split[0], split[1]);
                }
            } catch (IOException e) {
                log.error("Error reading file: {}", e.getMessage());
            }
        } else {
            log.info("File terrakube_env does not exist");
        }

        processLauncher.setEnvironmentVariable("bashToolsDirectory", getBashToolsDirectory(workingDirectory).getAbsolutePath());
        processLauncher.setEnvironmentVariable("terrakubeToolsRepository", getToolsRepository(workingDirectory).getAbsolutePath());
        processLauncher.setEnvironmentVariable("terrakubeEnv", tempEnv);
        processLauncher.setEnvironmentVariable("workingDirectory", workingDirectory.getAbsolutePath());
        processLauncher.setEnvironmentVariable("organizationId", terraformJob.getOrganizationId());
        processLauncher.setEnvironmentVariable("workspaceId", terraformJob.getWorkspaceId());
        processLauncher.setEnvironmentVariable("jobId", terraformJob.getJobId());
        processLauncher.setEnvironmentVariable("stepId", terraformJob.getStepId());
        processLauncher.setEnvironmentVariable("terraformVersion", terraformJob.getTerraformVersion());
        processLauncher.setEnvironmentVariable("source", terraformJob.getSource());
        processLauncher.setEnvironmentVariable("branch", terraformJob.getBranch());
        processLauncher.setEnvironmentVariable("vcsType", terraformJob.getVcsType() != null ? terraformJob.getVcsType() : "");
        processLauncher.setEnvironmentVariable("accessToken", terraformJob.getAccessToken() != null ? terraformJob.getAccessToken() : "");
        processLauncher.setEnvironmentVariable("terraformOutput", terraformJob.getTerraformOutput() != null ? terraformJob.getTerraformOutput() : "");
        processLauncher.setEnvironmentVariable("terrakubeApi", this.terrakubeApi);
        processLauncher.setEnvironmentVariable("terrakubeToken", workspaceSecurity.generateAccessToken(5));
        terraformJob.getEnvironmentVariables().forEach((key, value) -> processLauncher.setEnvironmentVariable(key, value));
        terraformJob.getVariables().forEach(variable -> processLauncher.setEnvironmentVariable(variable.getKey(), variable.getValue()));
        processLauncher.setOrAppendEnvironmentVariable("PATH", workingDirectory.getAbsolutePath() + ScriptEngineService.TOOLS, ":");

        //Adding terraform to the process PATH
        String bashToolsCompletePath = String.join(
                ":",
                loadingBashTools(workingDirectory),
                FileUtils.getUserDirectoryPath() + TERRAFORM_DIRECTORY + terraformJob.getTerraformVersion(),
                FileUtils.getUserDirectoryPath() + TOFU_DIRECTORY + terraformJob.getTerraformVersion()
        );

        log.info("Job Complete Path {}", bashToolsCompletePath);
        processLauncher.setOrAppendEnvironmentVariable("PATH", bashToolsCompletePath, ":");

        processLauncher.setOutputListener(outputListener);
        processLauncher.setErrorListener(errorListener);

        return processLauncher;
    }

    private String loadingBashTools(File workingDirectory) {
        // Loading Bash scripts from central repository /.terrakube/toolsRepository and adding to the process PATH
        Collection<File> bashTools = FileUtils.listFiles(getToolsRepository(workingDirectory), new String[]{"sh"}, true);
        bashTools.stream().forEach(bash -> bash.setExecutable(true));

        String bashToolsCompletePath = bashTools.stream()
                .map(bash -> bash.getParentFile().getAbsolutePath())
                .collect(Collectors.joining(":"));

        // Loading Bash scripts from working directory if the terraform module has some sh files
        Collection<File> bashToolsWorkspace = FileUtils.listFiles(workingDirectory, new String[]{"sh"}, true);
        bashToolsWorkspace.stream().forEach(bash -> bash.setExecutable(true));

        String bashToolsWorkspacePath = bashToolsWorkspace.stream()
                .map(bash -> bash.getParentFile().getAbsolutePath())
                .collect(Collectors.joining(":"));

        String externalToolsCompletePath = "";

        // Search all external tools inside folder /.terrakube/tools
        if(getBashToolsDirectory(workingDirectory).exists()){
            Collection<File> externalTools = FileUtils.listFilesAndDirs(
                getBashToolsDirectory(workingDirectory),
                new NotFileFilter(TrueFileFilter.INSTANCE),
                TrueFileFilter.TRUE
            );

            // Show folders with tools
            externalTools.forEach(tool -> log.info("External: {}", tool.getName()));

            // Load to the process PATH
            externalToolsCompletePath = externalTools.stream()
            .map(externalTool -> externalTool.getAbsolutePath())
            .collect(Collectors.joining(":"));
        }

        bashToolsCompletePath = String.join(":", externalToolsCompletePath, bashToolsCompletePath, bashToolsWorkspacePath);
        return bashToolsCompletePath;
    }

    private File getToolsRepository(File workingDirectory) {
        return new File(workingDirectory.getAbsolutePath() + ScriptEngineService.TOOLS_REPOSITORY);
    }

    private File getBashToolsDirectory(File workingDirectory) {
        return new File(workingDirectory.getAbsolutePath() + ScriptEngineService.TOOLS);
    }
}