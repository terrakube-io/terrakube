package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class PlanStructuredOutputService {

    private static final String TERRAFORM_BINARY = "terraform";
    private static final String TERRAFORM_DIRECTORY = "/.terraform-spring-boot/terraform/";
    private static final String TERRAFORM_PLAN_FILE = "terraformLibrary.tfPlan";
    private static final String CONTEXT_PLAN_KEY = "planStructuredOutput";
    private static final String CONTEXT_UI_KEY = "terrakubeUI";
    private static final String STRUCTURED_PLAN_MARKER = "<div data-terrakube-structured-plan=\"true\"></div>";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final WorkspaceSecurity workspaceSecurity;
    private final ObjectMapper objectMapper;
    private final String terrakubeApiUrl;

    public PlanStructuredOutputService(
            WorkspaceSecurity workspaceSecurity,
            ObjectMapper objectMapper,
            @Value("${io.terrakube.api.url}") String terrakubeApiUrl) {
        this.workspaceSecurity = workspaceSecurity;
        this.objectMapper = objectMapper;
        this.terrakubeApiUrl = terrakubeApiUrl;
    }

    public void publishPlanSummary(TerraformJob terraformJob, File terraformWorkingDir) {
        try {
            String planJson = getPlanAsJson(terraformJob, terraformWorkingDir);
            if (planJson == null || planJson.isBlank()) {
                return;
            }

            List<Map<String, Object>> changes = buildChangesFromPlanJson(planJson);
            Map<String, Object> context = getCurrentContext(terraformJob.getJobId());
            Map<String, Object> updatedContext = updateContext(context, terraformJob.getStepId(), changes);
            saveContext(terraformJob.getJobId(), updatedContext);
        } catch (Exception e) {
            log.warn("Unable to publish structured plan output for job {} step {}", terraformJob.getJobId(),
                    terraformJob.getStepId(), e);
        }
    }

    String getPlanAsJson(TerraformJob terraformJob, File terraformWorkingDir) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(
                resolveTerraformBinary(terraformJob),
                "show",
                "-json",
                TERRAFORM_PLAN_FILE);
        processBuilder.directory(terraformWorkingDir);
        processBuilder.redirectErrorStream(true);
        applyExecutionEnvironment(processBuilder, terraformJob, terraformWorkingDir);
        Process process = processBuilder.start();

        String commandOutput;
        try (InputStream processOutput = process.getInputStream()) {
            commandOutput = new String(processOutput.readAllBytes(), StandardCharsets.UTF_8);
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.warn("terraform show -json returned {}: {}", exitCode, commandOutput);
            return null;
        }

        return commandOutput;
    }

    List<Map<String, Object>> buildChangesFromPlanJson(String json) throws IOException {
        Map<String, Object> plan = objectMapper.readValue(json, new TypeReference<>() {
        });
        List<Map<String, Object>> resourceChanges = (List<Map<String, Object>>) plan.getOrDefault("resource_changes", new ArrayList<>());

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> change : resourceChanges) {
            Map<String, Object> changeBlock = (Map<String, Object>) change.get("change");
            if (changeBlock == null) {
                continue;
            }

            List<String> actions = (List<String>) changeBlock.getOrDefault("actions", List.of());
            String action = normalizeAction(actions);
            if ("no-op".equals(action)) {
                continue;
            }

            Map<String, Object> entry = new HashMap<>();
            entry.put("address", change.get("address"));
            entry.put("moduleAddress", change.get("module_address"));
            entry.put("resourceType", change.get("type"));
            entry.put("resourceName", change.get("name"));
            entry.put("actions", actions);
            entry.put("action", action);
            Object beforeValue = changeBlock.get("before");
            Object afterValue = changeBlock.get("after");
            Object beforeSensitive = changeBlock.get("before_sensitive");
            Object afterSensitive = changeBlock.get("after_sensitive");
            Object changedSensitive = collectChangedSensitivePaths(
                    beforeValue,
                    afterValue,
                    beforeSensitive,
                    afterSensitive);
            entry.put("before", sanitizeSensitiveValues(beforeValue, beforeSensitive));
            entry.put("beforeSensitive", beforeSensitive);
            entry.put("after", sanitizeSensitiveValues(afterValue, afterSensitive));
            entry.put("afterSensitive", afterSensitive);
            if (changedSensitive != null) {
                entry.put("changedSensitive", changedSensitive);
            }
            entry.put("afterUnknown", changeBlock.get("after_unknown"));
            result.add(entry);
        }
        return result;
    }

    Map<String, Object> updateContext(Map<String, Object> context, String stepId, List<Map<String, Object>> changes) {
        Map<String, Object> updatedContext = new HashMap<>(context);

        Map<String, Object> planStructuredOutput = toMap(updatedContext.get(CONTEXT_PLAN_KEY));
        planStructuredOutput.put(stepId, changes);
        updatedContext.put(CONTEXT_PLAN_KEY, planStructuredOutput);

        Map<String, Object> terrakubeUi = toMap(updatedContext.get(CONTEXT_UI_KEY));
        terrakubeUi.put(stepId, STRUCTURED_PLAN_MARKER);
        updatedContext.put(CONTEXT_UI_KEY, terrakubeUi);

        return updatedContext;
    }

    private Map<String, Object> getCurrentContext(String jobId) {
        HttpURLConnection connection = null;
        try {
            connection = buildConnection(terrakubeApiUrl + "/context/v1/" + jobId, "GET");
            int statusCode = connection.getResponseCode();
            if (statusCode >= 400) {
                log.warn("Unable to read context for job {}. Response status: {}", jobId, statusCode);
                return new HashMap<>();
            }

            String body = readResponseBody(connection);
            if (body == null || body.isBlank()) {
                return new HashMap<>();
            }

            return objectMapper.readValue(body, new TypeReference<>() {
            });
        } catch (Exception ex) {
            log.warn("Unable to read context for job {}", jobId, ex);
            return new HashMap<>();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void saveContext(String jobId, Map<String, Object> context) {
        HttpURLConnection connection = null;
        try {
            connection = buildConnection(terrakubeApiUrl + "/context/v1/" + jobId, "POST");
            connection.setDoOutput(true);
            byte[] data = objectMapper.writeValueAsBytes(context);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(data);
            }

            int statusCode = connection.getResponseCode();
            if (statusCode >= 400) {
                log.warn("Unable to save context for job {}. Response status: {} Body: {}", jobId, statusCode,
                        readResponseBody(connection));
            }
        } catch (Exception e) {
            log.warn("Unable to save context for job {}", jobId, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection buildConnection(String endpoint, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Authorization", "Bearer " + workspaceSecurity.generateAccessToken(5));
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setUseCaches(false);
        return connection;
    }

    private Map<String, Object> toMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> typed = new HashMap<>();
            map.forEach((k, v) -> typed.put(String.valueOf(k), v));
            return typed;
        }
        return new HashMap<>();
    }

    private void applyExecutionEnvironment(ProcessBuilder processBuilder, TerraformJob terraformJob,
            File terraformWorkingDir) {
        processBuilder.environment().put("TF_IN_AUTOMATION", "true");
        processBuilder.environment().putAll(loadTempEnvironmentVariables(terraformWorkingDir));

        if (terraformJob.getTerraformVersion() == null || terraformJob.getTerraformVersion().isBlank()) {
            return;
        }

        String terraformPath = FileUtils.getUserDirectoryPath() + TERRAFORM_DIRECTORY + terraformJob.getTerraformVersion();
        String currentPath = processBuilder.environment().get("PATH");
        if (currentPath == null || currentPath.isBlank()) {
            processBuilder.environment().put("PATH", terraformPath);
            return;
        }

        processBuilder.environment().put("PATH", terraformPath + File.pathSeparator + currentPath);
    }

    private Map<String, String> loadTempEnvironmentVariables(File terraformWorkingDir) {
        Map<String, String> environmentVariables = new HashMap<>();
        File tempEnvironmentFile = new File(terraformWorkingDir, ".terrakube_temp_env");
        if (!tempEnvironmentFile.exists()) {
            return environmentVariables;
        }

        try {
            List<String> lines = FileUtils.readLines(tempEnvironmentFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                int separatorIndex = line.indexOf('=');
                if (separatorIndex <= 0) {
                    continue;
                }

                String key = line.substring(0, separatorIndex);
                String value = line.substring(separatorIndex + 1);
                environmentVariables.put(key, value);
            }
        } catch (IOException exception) {
            log.warn("Unable to load temporary environment variables from {}", tempEnvironmentFile.getAbsolutePath(),
                    exception);
        }

        return environmentVariables;
    }

    private String readResponseBody(HttpURLConnection connection) throws IOException {
        InputStream stream = connection.getErrorStream();
        if (stream == null) {
            stream = connection.getInputStream();
        }

        if (stream == null) {
            return "";
        }

        try (InputStream responseStream = stream) {
            return new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String resolveTerraformBinary(TerraformJob terraformJob) {
        if (terraformJob.getTerraformVersion() == null || terraformJob.getTerraformVersion().isBlank()) {
            return TERRAFORM_BINARY;
        }

        File terraformBinary = new File(
                FileUtils.getUserDirectoryPath() + TERRAFORM_DIRECTORY + terraformJob.getTerraformVersion() + "/terraform");

        if (terraformBinary.exists() && terraformBinary.canExecute()) {
            return terraformBinary.getAbsolutePath();
        }

        log.warn("Terraform binary not found at {}. Falling back to PATH resolution.",
                terraformBinary.getAbsolutePath());
        return TERRAFORM_BINARY;
    }

    private String normalizeAction(List<String> actions) {
        if (actions.contains("delete") && actions.contains("create")) {
            return "replace";
        }

        if (actions.contains("create")) {
            return "create";
        }

        if (actions.contains("delete")) {
            return "delete";
        }

        if (actions.contains("update")) {
            return "update";
        }

        if (actions.contains("read")) {
            return "read";
        }

        if (actions.contains("no-op")) {
            return "no-op";
        }

        return "unknown";
    }

    Object sanitizeSensitiveValues(Object value, Object sensitiveMetadata) {
        if (Boolean.TRUE.equals(sensitiveMetadata)) {
            return null;
        }

        if (value instanceof Map<?, ?> valueMap) {
            Map<String, Object> sanitizedMap = new HashMap<>();
            Map<?, ?> sensitiveMap = sensitiveMetadata instanceof Map<?, ?> ? (Map<?, ?>) sensitiveMetadata : Map.of();

            valueMap.forEach((key, entryValue) -> sanitizedMap.put(
                    String.valueOf(key),
                    sanitizeSensitiveValues(entryValue, sensitiveMap.get(key))));
            return sanitizedMap;
        }

        if (value instanceof List<?> valueList) {
            List<?> sensitiveList = sensitiveMetadata instanceof List<?> ? (List<?>) sensitiveMetadata : List.of();
            List<Object> sanitizedList = new ArrayList<>();

            for (int index = 0; index < valueList.size(); index++) {
                Object sensitiveEntry = index < sensitiveList.size() ? sensitiveList.get(index) : null;
                sanitizedList.add(sanitizeSensitiveValues(valueList.get(index), sensitiveEntry));
            }

            return sanitizedList;
        }

        return value;
    }

    // Compare raw values before redaction so the UI can hide unchanged secrets
    // without losing truly changed sensitive fields.
    private Object collectChangedSensitivePaths(Object before, Object after, Object beforeSensitive, Object afterSensitive) {
        if (isSensitiveLeaf(beforeSensitive, afterSensitive)) {
            if (valuesAreEqual(before, after)) {
                return null;
            }

            return true;
        }

        if (hasMapShape(before, after, beforeSensitive, afterSensitive)) {
            return collectChangedSensitiveMap(
                    asMap(before),
                    asMap(after),
                    asMap(beforeSensitive),
                    asMap(afterSensitive));
        }

        if (hasListShape(before, after, beforeSensitive, afterSensitive)) {
            return collectChangedSensitiveList(
                    asList(before),
                    asList(after),
                    asList(beforeSensitive),
                    asList(afterSensitive));
        }

        return null;
    }

    private boolean isSensitiveLeaf(Object beforeSensitive, Object afterSensitive) {
        return Boolean.TRUE.equals(beforeSensitive) || Boolean.TRUE.equals(afterSensitive);
    }

    private boolean hasMapShape(Object before, Object after, Object beforeSensitive, Object afterSensitive) {
        return before instanceof Map<?, ?>
                || after instanceof Map<?, ?>
                || beforeSensitive instanceof Map<?, ?>
                || afterSensitive instanceof Map<?, ?>;
    }

    private boolean hasListShape(Object before, Object after, Object beforeSensitive, Object afterSensitive) {
        return before instanceof List<?>
                || after instanceof List<?>
                || beforeSensitive instanceof List<?>
                || afterSensitive instanceof List<?>;
    }

    private Map<?, ?> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }

        return Map.of();
    }

    private List<?> asList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }

        return List.of();
    }

    private Object collectChangedSensitiveMap(
            Map<?, ?> beforeMap,
            Map<?, ?> afterMap,
            Map<?, ?> beforeSensitiveMap,
            Map<?, ?> afterSensitiveMap) {
        Map<String, Object> changedSensitivePaths = new LinkedHashMap<>();

        for (String key : collectChangedSensitiveKeys(beforeMap, afterMap, beforeSensitiveMap, afterSensitiveMap)) {
            Object changedChild = collectChangedSensitivePaths(
                    beforeMap.get(key),
                    afterMap.get(key),
                    beforeSensitiveMap.get(key),
                    afterSensitiveMap.get(key));
            if (changedChild != null) {
                changedSensitivePaths.put(key, changedChild);
            }
        }

        if (changedSensitivePaths.isEmpty()) {
            return null;
        }

        return changedSensitivePaths;
    }

    private Set<String> collectChangedSensitiveKeys(
            Map<?, ?> beforeMap,
            Map<?, ?> afterMap,
            Map<?, ?> beforeSensitiveMap,
            Map<?, ?> afterSensitiveMap) {
        Set<String> keys = new LinkedHashSet<>();
        addMapKeys(keys, beforeMap);
        addMapKeys(keys, afterMap);
        addMapKeys(keys, beforeSensitiveMap);
        addMapKeys(keys, afterSensitiveMap);
        return keys;
    }

    private void addMapKeys(Set<String> keys, Map<?, ?> map) {
        map.keySet().forEach((key) -> keys.add(String.valueOf(key)));
    }

    private Object collectChangedSensitiveList(
            List<?> beforeList,
            List<?> afterList,
            List<?> beforeSensitiveList,
            List<?> afterSensitiveList) {
        int maxLength = Math.max(
                Math.max(beforeList.size(), afterList.size()),
                Math.max(beforeSensitiveList.size(), afterSensitiveList.size()));
        List<Object> changedSensitivePaths = new ArrayList<>();
        boolean hasChanges = false;

        for (int index = 0; index < maxLength; index++) {
            Object changedChild = collectChangedSensitivePaths(
                    getListValue(beforeList, index),
                    getListValue(afterList, index),
                    getListValue(beforeSensitiveList, index),
                    getListValue(afterSensitiveList, index));
            changedSensitivePaths.add(changedChild);
            if (changedChild != null) {
                hasChanges = true;
            }
        }

        if (!hasChanges) {
            return null;
        }

        return changedSensitivePaths;
    }

    private Object getListValue(List<?> list, int index) {
        if (index >= list.size()) {
            return null;
        }

        return list.get(index);
    }

    private boolean valuesAreEqual(Object left, Object right) {
        return objectMapper.valueToTree(left).equals(objectMapper.valueToTree(right));
    }
}
