package io.terrakube.api.plugin.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.security.encryption.EncryptionService;
import io.terrakube.api.rs.job.Job;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class JobVariablesService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EncryptionService encryptionService;

    public void validate(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) return;
        if (variables.size() > 100) throw new IllegalArgumentException("At most 100 job variables are allowed");
        for (Map.Entry<?, ?> entry : variables.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !key.matches("[A-Za-z_][A-Za-z0-9_-]*")
                    || !(entry.getValue() instanceof String value) || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Job variables require valid Terraform names and string values without NUL characters");
            }
        }
        if (json(variables).getBytes(StandardCharsets.UTF_8).length > 65_536) {
            throw new IllegalArgumentException("Job variables exceed 64 KiB");
        }
    }

    private String json(Map<String, String> variables) {
        try {
            return MAPPER.writeValueAsString(variables);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Could not encode job variables");
        }
    }

    public String encrypt(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) return null;
        validate(variables);
        return encryptionService.encrypt(json(variables));
    }

    public Map<String, String> decrypt(String encrypted) {
        if (encrypted == null) return null;
        try {
            return MAPPER.readValue(encryptionService.decrypt(encrypted), new TypeReference<>() {});
        } catch (Exception e) {
            // Parser errors can contain fragments of the secret JSON document.
            throw new IllegalStateException("Could not read encrypted job variables");
        }
    }

    public void merge(Job job, Map<String, String> terraform, Map<String, String> environment) {
        Map<String, String> variables = job.getVariables();
        if (variables == null) return;
        variables.forEach((name, value) -> {
            // Keep raw TF_VAR semantics, including multiline strings and typed complex values.
            // Remove the workspace/template -var entry, which would otherwise take precedence.
            terraform.remove(name);
            environment.put("TF_VAR_" + name, value);
        });
    }
}
