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
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EncryptionService encryptionService;

    public void validate(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) return;
        if (variables.size() > MAX_VARIABLES) {
            throw new IllegalArgumentException("At most " + MAX_VARIABLES + " job variables are allowed");
        }
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            if (entry.getKey() == null || !NAME.matcher(entry.getKey()).matches()
                    || entry.getValue() == null || entry.getValue().indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Job variables require valid Terraform names and string values without NUL characters");
            }
        }
        if (json(variables).getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("Job variables exceed 64 KiB once encoded as JSON");
        }
    }

    private String json(Map<String, String> variables) {
        try {
            return MAPPER.writeValueAsString(variables);
        } catch (JsonProcessingException e) {
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
        } catch (Exception e) {
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
            environment.put("TF_VAR_" + name, value);
        });
    }
}
