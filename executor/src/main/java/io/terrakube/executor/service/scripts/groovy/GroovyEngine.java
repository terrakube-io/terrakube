package io.terrakube.executor.service.scripts.groovy;

import com.diogonunes.jcolor.AnsiFormat;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.scripts.CommandExecution;
import io.terrakube.executor.service.scripts.ScriptEngineService;
import io.terrakube.executor.service.scripts.bash.ProcessLauncher;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static com.diogonunes.jcolor.Ansi.colorize;
import static com.diogonunes.jcolor.Attribute.*;

@Slf4j
@Service
public class GroovyEngine implements CommandExecution {

    private static final String USER_GROOVY_SCRIPT = "/userScript.groovy";

    private static final Set<String> SAFE_SYSTEM_ENV = Set.of(
            "PATH", "HOME", "USER", "SHELL", "TMPDIR", "LANG", "LC_ALL", "TERM"
    );

    private final ExecutorService executor = Executors.newWorkStealingPool();

    private String terrakubeApi;

    private WorkspaceSecurity workspaceSecurity;

    @Autowired
    public GroovyEngine(WorkspaceSecurity workspaceSecurity, @Value("${io.terrakube.api.url}") String terrakubeApi) {
        this.terrakubeApi = terrakubeApi;
        this.workspaceSecurity = workspaceSecurity;
    }

    public GroovyEngine(TerrakubeClient terrakubeClient, WorkspaceSecurity workspaceSecurity, String terrakubeApi) {
        this(workspaceSecurity, terrakubeApi);
    }

    @Override
    public boolean execute(TerraformJob terraformJob, String scriptContent, File terraformWorkingDir, Consumer<String> output) {
        boolean executeSuccess = true;
        File groovyScript = new File(
                FilenameUtils.separatorsToSystem(
                        terraformWorkingDir.getAbsolutePath().concat(USER_GROOVY_SCRIPT)
                )
        );

        try {
            log.info("ScriptPath: {}", groovyScript.toURI().toURL());
            FileUtils.writeStringToFile(groovyScript, scriptContent, Charset.defaultCharset());

            log.info("Execute Groovy scriptContent: \n {}", scriptContent);
            ProcessLauncher processLauncher = setupGroovyProcess(terraformJob, terraformWorkingDir, groovyScript, output, output);
            Integer exitCode = processLauncher.launch().get();
            log.info("Exit code {}", exitCode);
            if (exitCode != 0) {
                log.error("Script Exit Code {} \n Script \n {}", terraformJob.getJobId(), scriptContent);
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

    private ProcessLauncher setupGroovyProcess(TerraformJob terraformJob, File workingDirectory, File groovyScript, Consumer<String> outputListener, Consumer<String> errorListener) {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();
        String classpath = System.getProperty("java.class.path");
        File toolsRepo = getToolsRepository(workingDirectory);

        List<String> command = new java.util.ArrayList<>();
        command.add(javaExecutable);

        String effectiveClasspath = resolveClasspathForGroovy(classpath);
        if (effectiveClasspath != null && !effectiveClasspath.isBlank()) {
            command.add("-cp");
            command.add(effectiveClasspath);
        }

        if (isSpringBootFatJar(classpath)) {
            command.add("-Dloader.main=" + GroovyProcessRunner.class.getName());
            command.add("org.springframework.boot.loader.launch.PropertiesLauncher");
        } else {
            command.add(GroovyProcessRunner.class.getName());
        }

        command.add(groovyScript.getAbsolutePath());
        command.add(toolsRepo.getAbsolutePath());

        ProcessLauncher processLauncher = new ProcessLauncher(
                this.executor,
                command.toArray(new String[0])
        );
        processLauncher.setDirectory(workingDirectory);

        // Sanitize child process environment to prevent ambient executor secrets leakage (TK-01)
        processLauncher.clearEnvironment();
        for (String safeKey : SAFE_SYSTEM_ENV) {
            String val = System.getenv(safeKey);
            if (val != null && !val.isBlank()) {
                processLauncher.setEnvironmentVariable(safeKey, val);
            }
        }

        String tempEnv = workingDirectory.getAbsolutePath() + "/.terrakube_temp_env";
        Path path = Paths.get(tempEnv);
        if (Files.exists(path)) {
            log.info("File .terrakube_env exists");
            try (BufferedReader reader = Files.newBufferedReader(path)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int eqIdx = line.indexOf('=');
                    if (eqIdx > 0) {
                        String key = line.substring(0, eqIdx);
                        String val = line.substring(eqIdx + 1);
                        log.info("Loading {}", key);
                        processLauncher.setEnvironmentVariable(key, val);
                    }
                }
            } catch (IOException e) {
                log.error("Error reading file: {}", e.getMessage());
            }
        } else {
            log.info("File terrakube_env does not exist");
        }

        processLauncher.setEnvironmentVariable("bashToolsDirectory", getBashToolsDirectory(workingDirectory).getAbsolutePath());
        processLauncher.setEnvironmentVariable("terrakubeToolsRepository", toolsRepo.getAbsolutePath());
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
        processLauncher.setEnvironmentVariable("terraformOutputJson", terraformJob.getTerraformOutput() != null ? terraformJob.getTerraformOutput() : "");
        processLauncher.setEnvironmentVariable("terrakubeApi", this.terrakubeApi);
        processLauncher.setEnvironmentVariable("terrakubeToken", workspaceSecurity.generateAccessToken(5,
                terraformJob.getOrganizationId(),
                terraformJob.getWorkspaceId(),
                terraformJob.getJobId(),
                terraformJob.getStepId()));
        terraformJob.getEnvironmentVariables().forEach((key, value) -> processLauncher.setEnvironmentVariable(key, value));
        terraformJob.getVariables().forEach((key, value) -> processLauncher.setEnvironmentVariable(key, value));
        processLauncher.setOrAppendEnvironmentVariable("PATH", workingDirectory.getAbsolutePath() + ScriptEngineService.TOOLS, ":");

        processLauncher.setOutputListener(outputListener);
        processLauncher.setErrorListener(errorListener);

        return processLauncher;
    }

    private File getToolsRepository(File workingDirectory) {
        return new File(workingDirectory.getAbsolutePath() + ScriptEngineService.TOOLS_REPOSITORY);
    }

    private File getBashToolsDirectory(File workingDirectory) {
        return new File(workingDirectory.getAbsolutePath() + ScriptEngineService.TOOLS);
    }

    String resolveClasspathForGroovy(String classpath) {
        if (classpath == null || classpath.isBlank()) {
            return "";
        }

        java.util.List<String> entries = new java.util.ArrayList<>(java.util.Arrays.asList(classpath.split(File.pathSeparator)));
        java.util.List<String> additionalEntries = new java.util.ArrayList<>();

        for (String entry : entries) {
            File dir = new File(entry);
            if (dir.isDirectory()) {
                File bootInfClasses = new File(dir, "BOOT-INF/classes");
                File bootInfLib = new File(dir, "BOOT-INF/lib");
                if (bootInfClasses.isDirectory()) {
                    additionalEntries.add(bootInfClasses.getAbsolutePath());
                }
                if (bootInfLib.isDirectory()) {
                    additionalEntries.add(bootInfLib.getAbsolutePath() + "/*");
                }
            }
        }

        for (String candidate : java.util.List.of(".", "/workspace")) {
            File dir = new File(candidate);
            if (dir.isDirectory()) {
                File bootInfClasses = new File(dir, "BOOT-INF/classes");
                File bootInfLib = new File(dir, "BOOT-INF/lib");
                if (bootInfClasses.isDirectory() && !additionalEntries.contains(bootInfClasses.getAbsolutePath())) {
                    additionalEntries.add(bootInfClasses.getAbsolutePath());
                }
                if (bootInfLib.isDirectory() && !additionalEntries.contains(bootInfLib.getAbsolutePath() + "/*")) {
                    additionalEntries.add(bootInfLib.getAbsolutePath() + "/*");
                }
            }
        }

        if (!additionalEntries.isEmpty()) {
            entries.addAll(additionalEntries);
            return String.join(File.pathSeparator, entries);
        }

        return classpath;
    }

    boolean isSpringBootFatJar(String classpath) {
        if (classpath == null) return false;
        String[] parts = classpath.split(File.pathSeparator);
        if (parts.length == 1 && parts[0].endsWith(".jar")) {
            File jar = new File(parts[0]);
            if (jar.isFile()) {
                try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
                    return jf.getEntry("BOOT-INF/classes/") != null;
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }
}