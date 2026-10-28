package io.terrakube.executor.service.terraform;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The variable file of a Terraform CLI run. Terraform reads a {@code --var} value of a variable without a type
 * constraint as a string, so a list, object, number or boolean the CLI took from a variable file would lose its type.
 * The API sends those as HCL literals, and this writes them to a file that {@code -var-file} reads, which keeps the
 * type, exactly like a local run.
 *
 * <p>The file is passed through {@code TF_CLI_ARGS_plan} and {@code TF_CLI_ARGS_destroy}, never to an apply of a saved
 * plan, where Terraform refuses variables. It is not an {@code *.auto.tfvars} file, so no other command reads it.
 */
@Slf4j
final class RunVariablesFile {
    static final String FILE_NAME = "terrakube-run-variables.tfvars";
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");

    private RunVariablesFile() {
    }

    /** Writes the variables as {@code name = literal} lines and returns the file, readable by its owner only. */
    static File write(File directory, Map<String, String> hclVariables) throws IOException {
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, String> variable : new TreeMap<>(hclVariables).entrySet()) {
            if (!NAME.matcher(variable.getKey()).matches() || variable.getValue() == null || variable.getValue().isBlank()) {
                throw new IOException("Invalid run variable");
            }
            content.append(variable.getKey()).append(" = ").append(variable.getValue()).append('\n');
        }
        File file = new File(directory, FILE_NAME);
        Files.deleteIfExists(file.toPath());
        try {
            Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException _) {
            Files.createFile(file.toPath());
        }
        Files.writeString(file.toPath(), content.toString(), StandardCharsets.UTF_8);
        return file;
    }

    static void delete(File directory) {
        FileUtils.deleteQuietly(new File(directory, FILE_NAME));
    }

    /** Adds {@code -var-file} to the arguments Terraform reads from a {@code TF_CLI_ARGS_<command>} variable. */
    static String addVarFile(String existingArguments, File file) {
        // Terraform splits the value like a shell does, so the whole word is single quoted.
        String argument = "'-var-file=" + file.getAbsolutePath().replace("'", "'\\''") + "'";
        return existingArguments == null || existingArguments.isBlank() ? argument : existingArguments + " " + argument;
    }
}
