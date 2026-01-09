package io.terrakube.executor.service.scripts;

import com.diogonunes.jcolor.AnsiFormat;
import io.terrakube.executor.service.mode.Command;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.scripts.bash.BashEngine;
import io.terrakube.executor.service.scripts.groovy.GroovyEngine;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static com.diogonunes.jcolor.Ansi.colorize;
import static com.diogonunes.jcolor.Attribute.*;

@Slf4j
@Service
public class ScriptEngineService {

    public static final String TOOLS_REPOSITORY = "/.terrakube/toolsRepository";
    public static final String TOOLS = "/.terrakube/tools";

    private GroovyEngine groovyEngine;
    private BashEngine bashEngine;
    private String toolsRepository;
    private String toolsBranch;

    @Autowired
    public ScriptEngineService(GroovyEngine groovyEngine, BashEngine bashEngine,
            @Value("${io.terrakube.tools.repository}") String toolsRepository,
            @Value("${io.terrakube.tools.branch}") String toolsBranch) {
        this.groovyEngine = groovyEngine;
        this.bashEngine = bashEngine;
        this.toolsRepository = toolsRepository;
        this.toolsBranch = toolsBranch;
    }

    public boolean execute(TerraformJob terraformJob, List<Command> commands, File terraformWorkingDir,
            Consumer<String> output) {
        AtomicBoolean executeSuccess = new AtomicBoolean(true);
        TreeMap<Integer, Command> commandOrder = new TreeMap<>();
        if (commands != null) {
            log.debug("Executing scripts: {}", commands);
            commands.forEach(command -> {
                commandOrder.put(command.getPriority(), command);
            });

            try {
                createToolsDirectory(terraformWorkingDir, terraformJob);

                commandOrder.forEach((priority, command) -> {
                    if (executeSuccess.get()) {
                        if (command.isVerbose()) {
                            printBannerInit(command, output);

                        }
                        switch (command.getRuntime()) {
                            case GROOVY:
                                executeSuccess.set(groovyEngine.execute(terraformJob, command.getScript(),
                                        terraformWorkingDir, output));
                                break;
                            case BASH:
                                executeSuccess.set(bashEngine.execute(terraformJob, command.getScript(),
                                        terraformWorkingDir, output));
                                break;
                            default:
                                break;
                        }
                        if (command.isVerbose()) {
                            printBannerEnd(output);
                        }
                    }
                });

                cleanToolsDirectory(terraformWorkingDir);
            } catch (IOException e) {
                log.error(e.getMessage());
            }
        }
        return executeSuccess.get();

    }

    private void printBannerInit(Command command, Consumer<String> output) {
        AnsiFormat colorMessage = new AnsiFormat(GREEN_TEXT(), BLACK_BACK(), BOLD());
        AnsiFormat colorScript = new AnsiFormat(GREEN_TEXT(), BLACK_BACK(), BOLD());
        output.accept(colorize("***************************************", colorMessage));
        output.accept(colorize("Command Priority " + command.getPriority(), colorMessage));
        output.accept(colorize("Running " + command.getRuntime(), colorMessage));
        output.accept(colorize("Script Information:  ", colorMessage));
        output.accept(colorize(command.getScript(), colorScript));
        output.accept(colorize("\n\n\n***************************************", colorMessage));
        output.accept(colorize("Begin Execution: ", colorMessage));
    }

    private void printBannerEnd(Consumer<String> output) {
        AnsiFormat colorMessage = new AnsiFormat(GREEN_TEXT(), BLACK_BACK(), BOLD());
        output.accept(colorize("End Execution... ", colorMessage));
        output.accept(colorize("\n\n", colorMessage));
    }

    private void createToolsDirectory(File terraformWorkingDir, TerraformJob terraformJob) throws IOException {
        FileUtils.deleteDirectory(getToolsRepository(terraformWorkingDir));
        FileUtils.forceMkdir(getToolsRepository(terraformWorkingDir));
        String privateRepositoryType = "PUBLIC";
        privateRepositoryType = terraformJob.getEnvironmentVariables().getOrDefault("TERRAKUBE_PRIVATE_EXTENSION_REPO_TYPE", privateRepositoryType);
        String privateRepositoryToken = terraformJob.getEnvironmentVariables().getOrDefault("TERRAKUBE_PRIVATE_EXTENSION_REPO_TOKEN", null);
        String privateRepositoryTokenType = terraformJob.getEnvironmentVariables().getOrDefault("TERRAKUBE_PRIVATE_EXTENSION_REPO_TOKEN_TYPE", null);
        try {
            CredentialsProvider credentialsProvider;
            log.info("Private Extension vcsType: {}", privateRepositoryType);
            switch (privateRepositoryType) {
                case "GITLAB":
                    credentialsProvider = new UsernamePasswordCredentialsProvider("oauth2", privateRepositoryToken);
                    break;
                case "BITBUCKET":
                    credentialsProvider = new UsernamePasswordCredentialsProvider("x-token-auth",
                            privateRepositoryToken);
                    break;
                case "AZURE_DEVOPS", "AZURE_SP_MI":
                    credentialsProvider = new UsernamePasswordCredentialsProvider("dummy", privateRepositoryToken);
                    break;
                case "GITHUB":
                    if (privateRepositoryTokenType != null && privateRepositoryTokenType.equals("OAUTH")) {
                        credentialsProvider = new UsernamePasswordCredentialsProvider(privateRepositoryToken, "");
                    } else {
                        credentialsProvider = new UsernamePasswordCredentialsProvider("x-access-token",
                                privateRepositoryToken);
                    }
                    break;
                default:
                    credentialsProvider = null;
                    break;
            }

            Git.cloneRepository()
                    .setURI(this.toolsRepository)
                    .setDirectory(getToolsRepository(terraformWorkingDir))
                    .setBranch(this.toolsBranch)
                    .setCredentialsProvider(credentialsProvider)
                    .call();
        } catch (GitAPIException e) {
            log.error(e.getMessage());
        }
    }

    private File getToolsRepository(File terraformWorkingDir) {
        return new File(terraformWorkingDir.getAbsolutePath() + TOOLS_REPOSITORY);
    }

    private void cleanToolsDirectory(File terraformWorkingDir) throws IOException {
        FileUtils.deleteDirectory(getToolsRepository(terraformWorkingDir));
    }
}