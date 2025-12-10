package io.terrakube.executor.service.scripts.groovy;

import groovy.lang.Binding;
import groovy.util.GroovyScriptEngine;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.springframework.beans.factory.annotation.Value;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.scripts.CommandExecution;
import io.terrakube.executor.service.scripts.ScriptEngineService;
import org.springframework.stereotype.Service;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GroovyEngine implements CommandExecution {

    private static final String USER_GROOVY_SCRIPT = "/userScript.groovy";

    private TerrakubeClient terrakubeClient;

    private String terrakubeApi;

    private WorkspaceSecurity workspaceSecurity;

    public GroovyEngine(TerrakubeClient terrakubeClient, WorkspaceSecurity workspaceSecurity, @Value("${io.terrakube.api.url}") String terrakubeApi) {
        this.terrakubeClient = terrakubeClient;
        this.terrakubeApi = terrakubeApi;
        this.workspaceSecurity = workspaceSecurity;
    }

    @Override
    public boolean execute(TerraformJob terraformJob, String scriptContent, File terraformWorkingDir, Consumer<String> output) {
        boolean executeSuccess = true;
        File groovyScript = new File(terraformWorkingDir.getAbsolutePath() + USER_GROOVY_SCRIPT);

        try {
            log.info("ScriptPath: {}", groovyScript.toURI().toURL());
            FileUtils.writeStringToFile(groovyScript, scriptContent, Charset.defaultCharset());

            List<URL> groovyClasses = getGroovyExtensions(terraformWorkingDir);
            groovyClasses.add(groovyScript.toURI().toURL());

            log.info("Execute Groovy scriptContent: \n {}", scriptContent);
            final GroovyScriptEngine engine = new GroovyScriptEngine(
                    groovyClasses.toArray(URL[]::new),
                    this.getClass().getClassLoader()
            );

            Binding sharedData = setupBindings(terraformJob, terraformWorkingDir);
            sharedData.setProperty("terrakubeOutput", new ByteArrayOutputStream());

            engine.run(groovyScript.getName(), sharedData);

            ByteArrayOutputStream terrakubeOutput = (ByteArrayOutputStream) sharedData.getProperty("terrakubeOutput");
            String terrakubeOutputString = terrakubeOutput.toString(Charset.defaultCharset());

            log.info("Groovy output script \n{}", terrakubeOutputString);
            output.accept(terrakubeOutputString != null ? terrakubeOutputString : "Groovy Script completed\n");
        } catch (Exception exception) {
            log.error(exception.getMessage());
            executeSuccess = false;
            output.accept(exception.getMessage());
        }
        return executeSuccess;
    }

    private File getToolsRepository(File workingDirectory) {
        return new File(workingDirectory.getAbsolutePath() + ScriptEngineService.TOOLS_REPOSITORY);
    }

    private File getBashToolsDirectory(File workingDirectory) {
        return new File(workingDirectory.getAbsolutePath() + ScriptEngineService.TOOLS);
    }

    private Binding setupBindings(TerraformJob terraformJob, File workingDirectory) {
        Binding sharedData = new Binding();
        sharedData.setVariable("workingDirectory", workingDirectory.getAbsolutePath());
        sharedData.setVariable("organizationId", terraformJob.getOrganizationId());
        sharedData.setVariable("workspaceId", terraformJob.getWorkspaceId());
        sharedData.setVariable("jobId", terraformJob.getJobId());
        sharedData.setVariable("stepId", terraformJob.getStepId());
        sharedData.setVariable("terraformVersion", terraformJob.getTerraformVersion());
        sharedData.setVariable("source", terraformJob.getSource());
        sharedData.setVariable("branch", terraformJob.getBranch());
        sharedData.setVariable("terrakubeApi", this.terrakubeApi);
        sharedData.setVariable("terrakubeToken", workspaceSecurity.generateAccessToken(5));
        sharedData.setVariable("vcsType", terraformJob.getVcsType() != null ? terraformJob.getVcsType() : "");
        sharedData.setVariable("accessToken", terraformJob.getAccessToken() != null ? terraformJob.getAccessToken() : "");

        if(!terraformJob.getEnvironmentVariables().isEmpty()){
            terraformJob.getEnvironmentVariables().forEach((key,value)->{
                sharedData.setVariable(key,value);
            });
        }

        if(!terraformJob.getVariables().isEmpty()){
            terraformJob.getVariables().forEach(variable -> {
                sharedData.setVariable(variable.getKey(), variable.getValue());
            });
        }

        sharedData.setVariable("terraformOutput", terraformJob.getTerraformOutput() != null ? terraformJob.getTerraformOutput() : "");
        sharedData.setVariable("terrakubeClient", terrakubeClient);
        sharedData.setVariable("terraformOutputJson", terraformJob.getTerraformOutput() != null ? terraformJob.getTerraformOutput() : "");

        sharedData.setVariable("bashToolsDirectory", getBashToolsDirectory(workingDirectory).getAbsolutePath());
        sharedData.setVariable("terrakubeToolsRepository", getToolsRepository(workingDirectory).getAbsolutePath());
        return sharedData;
    }

    private List<URL> getGroovyExtensions(File workingDirectory) {

        Collection<File> groovyClasses = FileUtils.listFiles(getToolsRepository(workingDirectory), new String[]{"groovy"}, true);
        log.info("Found {} groovy files", groovyClasses.size());
        return groovyClasses.stream().map(groovyFile -> {
            try {
                log.info("Loading Groovy: {}", groovyFile.getName());
                return groovyFile.toURI().toURL();
            } catch (MalformedURLException e) {
                log.error(e.getMessage());
            }
            return null;
        }).collect(Collectors.toList());
    }
}