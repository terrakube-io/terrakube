package io.terrakube.executor.service.scripts.groovy;

import groovy.lang.Binding;
import groovy.util.GroovyScriptEngine;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class GroovyProcessRunner {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: GroovyProcessRunner <scriptPath> [toolsRepositoryPath]");
            System.exit(1);
        }

        try {
            File scriptFile = new File(args[0]).getAbsoluteFile();
            if (!scriptFile.exists()) {
                System.err.println("Script file not found: " + scriptFile.getAbsolutePath());
                System.exit(1);
            }

            List<URL> classRoots = new ArrayList<>();
            classRoots.add(scriptFile.getParentFile().toURI().toURL());

            if (args.length > 1 && args[1] != null && !args[1].isBlank()) {
                File toolsRepo = new File(args[1]).getAbsoluteFile();
                if (toolsRepo.exists()) {
                    classRoots.add(toolsRepo.toURI().toURL());
                    Collection<File> groovyFiles = FileUtils.listFiles(toolsRepo, new String[]{"groovy"}, true);
                    for (File f : groovyFiles) {
                        File dir = f.getParentFile();
                        if (dir != null) {
                            URL dirUrl = dir.toURI().toURL();
                            if (!classRoots.contains(dirUrl)) {
                                classRoots.add(dirUrl);
                            }
                        }
                    }
                }
            }

            GroovyScriptEngine engine = new GroovyScriptEngine(
                    classRoots.toArray(new URL[0]),
                    GroovyProcessRunner.class.getClassLoader()
            );

            Binding sharedData = new Binding();
            System.getenv().forEach(sharedData::setVariable);
            sharedData.setVariable("terrakubeOutput", System.out);

            engine.run(scriptFile.getName(), sharedData);
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
