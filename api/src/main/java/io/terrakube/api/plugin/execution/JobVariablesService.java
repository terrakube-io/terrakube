package io.terrakube.api.plugin.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.security.encryption.EncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class JobVariablesService {
    static final int MAX_VARIABLES = 100;
    static final int MAX_JSON_BYTES = 64 * 1024;
    private static final String ENV_PREFIX = "TF_VAR_";
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EncryptionService encryptionService;

    public void validate(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) return;
        if (variables.size() > MAX_VARIABLES) {
            throw new IllegalArgumentException("At most " + MAX_VARIABLES + " job variables are allowed");
        }
        // The generic types are erased at runtime, so a client sending a number or boolean can put a non-String in.
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) variables).entrySet()) {
            if (!(entry.getKey() instanceof String key) || !NAME.matcher(key).matches()
                    || !(entry.getValue() instanceof String value) || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Job variables require valid Terraform names and string values without NUL characters");
            }
            if (key.startsWith(ENV_PREFIX)) {
                throw new IllegalArgumentException("Job variable names must not start with " + ENV_PREFIX
                        + ", give the Terraform variable name itself");
            }
        }
        if (json(variables).getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("Job variables exceed 64 KiB once encoded as JSON");
        }
    }

    private String json(Map<String, String> variables) {
        try {
            return MAPPER.writeValueAsString(variables);
        } catch (JsonProcessingException _) {
            throw new IllegalArgumentException("Could not encode job variables");
        }
    }

    /** Validates and encrypts the variables. Returns null when there is nothing to store. */
    public String encrypt(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) return null;
        validate(variables);
        return encryptionService.encrypt(json(variables));
    }

    public Map<String, String> decrypt(String encrypted) {
        if (encrypted == null) return Map.of();
        try {
            return MAPPER.readValue(encryptionService.decrypt(encrypted), new TypeReference<>() {});
        } catch (Exception _) {
            // Parser errors can contain fragments of the secret JSON document.
            throw new IllegalStateException("Could not read encrypted job variables");
        }
    }

    /**
     * Applies the job variables as raw TF_VAR_ environment variables, which keeps multiline strings and
     * typed values intact. The workspace/template -var entry of the same name is dropped because it
     * would take precedence over the environment. Variable files and CLI arguments keep their normal
     * Terraform precedence.
     */
    public void merge(Map<String, String> variables, Map<String, String> terraform, Map<String, String> environment) {
        variables.forEach((name, value) -> {
            terraform.remove(name);
            environment.put(ENV_PREFIX + name, value);
        });
    }

    /**
     * Applies the variables of a CLI run, stored as HCL literals, as Terraform {@code --var} arguments, replacing
     * the workspace variable of the same name. Arguments rank above the configuration's own variable files
     * ({@code terraform.tfvars}, {@code *.auto.tfvars}), as {@code -var} does on a local run, which the
     * {@code TF_VAR_} environment variables of {@link #merge} do not.
     *
     * @return the literals that are not strings (numbers, booleans, lists, objects). Every variable is passed as a
     *         {@code --var} argument so that an executor that does not know about the return value keeps working,
     *         but Terraform reads such a value as a string when the variable has no type constraint. A newer
     *         executor therefore also writes these to a variable file, which keeps their type.
     */
    public Map<String, String> mergeAsArguments(Map<String, String> literals, Map<String, String> terraform) {
        Map<String, String> structured = new java.util.LinkedHashMap<>();
        try {
            literals.forEach((name, literal) -> {
                terraform.put(name, CliRunVariables.argumentValue(name, literal));
                if (!CliRunVariables.isString(literal)) {
                    structured.put(name, literal);
                }
            });
        } catch (IllegalArgumentException _) {
            // The message names the variable but a value must never reach a log or an error.
            throw new IllegalStateException("Could not read the stored job variables");
        }
        return structured;
    }
}
