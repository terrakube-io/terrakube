package io.terrakube.executor.service.infracost;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.configuration.InfracostExecutorProperties;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.scripts.bash.ProcessLauncher;
import io.terrakube.executor.service.terraform.JobContextService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;

@Slf4j
@Service
public class InfracostExecutorServiceImpl implements InfracostExecutorService {

    private static final String INFRACOST_DIR = ".terrakube-infracost";
    private static final String INFRACOST_OUT_FILE = "infracost.json";
    private static final String INFRACOST_OPA_DATA_FILE = "infracost-opa-data.json";
    private static final Pattern INFRACOST_TOKEN_PATTERN = Pattern.compile("ico-[A-Za-z0-9_-]{8,}");
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;
    private static final List<String> PASSTHROUGH_ENV_VARS = List.of(
            "PATH", "HOME", "TMPDIR", "TMP", "TEMP",
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY",
            "http_proxy", "https_proxy", "no_proxy",
            "SSL_CERT_FILE", "SSL_CERT_DIR"
    );

    private final InfracostBinaryService binaryService;
    private final InfracostExecutorProperties properties;
    private final JobContextService jobContextService;
    private final ObjectMapper objectMapper;
    private final Function<String, String> systemEnvLookup;
    private final ExecutorService processExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public InfracostExecutorServiceImpl(
            InfracostBinaryService binaryService,
            InfracostExecutorProperties properties,
            JobContextService jobContextService,
            ObjectMapper objectMapper) {
        this(binaryService, properties, jobContextService, objectMapper, System::getenv);
    }

    InfracostExecutorServiceImpl(
            InfracostBinaryService binaryService,
            InfracostExecutorProperties properties,
            JobContextService jobContextService,
            ObjectMapper objectMapper,
            Function<String, String> systemEnvLookup) {
        this.binaryService = binaryService;
        this.properties = properties != null ? properties : new InfracostExecutorProperties();
        this.jobContextService = jobContextService;
        this.objectMapper = objectMapper;
        this.systemEnvLookup = systemEnvLookup;
    }

    @Override
    public InfracostResult evaluateCost(TerraformJob job, File workingDirectory, File planJsonFile) {
        if (job == null || workingDirectory == null) {
            return skipped();
        }

        String enabledFlag = resolveSetting(job, "INFRACOST_ENABLED");
        if ("false".equalsIgnoreCase(enabledFlag)) {
            log.info("Infracost cost estimation disabled for job {} via INFRACOST_ENABLED=false", job.getJobId());
            return skipped();
        }

        String apiKey = resolveSetting(job, "INFRACOST_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("INFRACOST_API_KEY not configured for job {}; skipping cost estimation", job.getJobId());
            return skipped();
        }

        File workDir = new File(workingDirectory, INFRACOST_DIR);
        try {
            FileUtils.forceMkdir(workDir);
        } catch (IOException e) {
            return recordFailure(job, workDir, "Unable to initialize Infracost working directory", apiKey, e);
        }

        try {
            File configFile = resolveOptionalWorkspaceFile(workingDirectory, resolveSetting(job, "INFRACOST_CONFIG_FILE"), "infracost.yml");
            File usageFile = resolveOptionalWorkspaceFile(workingDirectory, resolveSetting(job, "INFRACOST_USAGE_FILE"), "infracost-usage.yml");

            if (configFile == null && (planJsonFile == null || !planJsonFile.isFile())) {
                return recordFailure(job, workDir, "Plan JSON missing; cost estimation skipped.", apiKey, null);
            }

            String version = resolveSetting(job, "INFRACOST_VERSION");
            File infracostBinary = binaryService.getInfracostBinary(version);
            File outputFile = new File(workDir, INFRACOST_OUT_FILE);

            List<String> command = buildCommand(infracostBinary, planJsonFile, configFile, usageFile, outputFile);
            ProcessLauncher launcher = new ProcessLauncher(processExecutor, command.toArray(String[]::new));
            launcher.setDirectory(workingDirectory);
            launcher.clearEnvironment();
            configureSubprocessEnvironment(launcher, job, apiKey);

            StringBuilder stdoutBuf = new StringBuilder();
            StringBuilder stderrBuf = new StringBuilder();
            launcher.setOutputListener(line -> appendBounded(stdoutBuf, line));
            launcher.setErrorListener(line -> appendBounded(stderrBuf, line));

            long timeoutSeconds = properties.getTimeoutSeconds() > 0 ? properties.getTimeoutSeconds() : 180L;
            int exitCode;
            try {
                exitCode = launcher.launch().get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                launcher.destroyForcibly();
                return recordFailure(job, workDir,
                        String.format("Infracost estimation timed out after %d seconds. Plan proceeded without cost estimation.", timeoutSeconds),
                        apiKey, te);
            }

            if (exitCode != 0 || !outputFile.isFile() || outputFile.length() == 0) {
                String details = stderrBuf.length() > 0 ? stderrBuf.toString().trim() : stdoutBuf.toString().trim();
                String message = details.isBlank()
                        ? String.format("Infracost CLI exited with code %d. Plan proceeded without cost estimation.", exitCode)
                        : String.format("Infracost CLI failed (exit %d): %s", exitCode, details);
                return recordFailure(job, workDir, message, apiKey, null);
            }

            JsonNode root = objectMapper.readTree(outputFile);
            Map<String, Object> normalized = normalizePayload(root, apiKey);

            persistToJobContext(job, normalized);
            File opaDataFile = writeOpaDataFile(workDir, normalized);

            log.info("Infracost estimation succeeded for job {}: totalMonthlyCost={} {}",
                    job.getJobId(), normalized.get("totalMonthlyCost"), normalized.get("currency"));

            return InfracostResult.builder()
                    .status(InfracostResult.Status.SUCCESS)
                    .normalizedContext(Collections.unmodifiableMap(normalized))
                    .infracostOpaDataFile(opaDataFile)
                    .build();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return recordFailure(job, workDir, "Infracost estimation was interrupted. Plan proceeded without cost estimation.", apiKey, ie);
        } catch (Exception e) {
            return recordFailure(job, workDir,
                    "Infracost estimation encountered an error: " + e.getMessage(),
                    apiKey, e);
        }
    }

    List<String> buildCommand(File infracostBinary, File planJsonFile, File configFile, File usageFile, File outputFile) {
        List<String> command = new ArrayList<>();
        command.add(infracostBinary.getAbsolutePath());
        command.add("breakdown");
        // Infracost CLI rejects combining --path and --config-file (and --usage-file with --config-file).
        if (configFile != null) {
            command.add("--config-file");
            command.add(configFile.getAbsolutePath());
        } else {
            command.add("--path");
            command.add(planJsonFile.getAbsolutePath());
            if (usageFile != null) {
                command.add("--usage-file");
                command.add(usageFile.getAbsolutePath());
            }
        }
        command.add("--format");
        command.add("json");
        command.add("--out-file");
        command.add(outputFile.getAbsolutePath());
        return command;
    }

    private void configureSubprocessEnvironment(ProcessLauncher launcher, TerraformJob job, String apiKey) {
        for (String var : PASSTHROUGH_ENV_VARS) {
            String value = systemEnvLookup.apply(var);
            if (value != null && !value.isBlank()) {
                launcher.setEnvironmentVariable(var, value);
            }
        }
        launcher.setEnvironmentVariable("INFRACOST_API_KEY", apiKey);

        String currency = resolveSetting(job, "INFRACOST_CURRENCY");
        if (currency == null || currency.isBlank()) {
            currency = properties.getDefaultCurrency();
        }
        if (currency != null && !currency.isBlank()) {
            launcher.setEnvironmentVariable("INFRACOST_CURRENCY", currency.trim());
        }

        String endpoint = resolveSetting(job, "INFRACOST_PRICING_API_ENDPOINT");
        if (endpoint != null && !endpoint.isBlank()) {
            launcher.setEnvironmentVariable("INFRACOST_PRICING_API_ENDPOINT", endpoint.trim());
        }

        String dashboard = resolveSetting(job, "INFRACOST_ENABLE_DASHBOARD");
        launcher.setEnvironmentVariable("INFRACOST_ENABLE_DASHBOARD",
                "true".equalsIgnoreCase(dashboard) ? "true" : "false");
        launcher.setEnvironmentVariable("INFRACOST_SKIP_UPDATE_CHECK", "true");
        launcher.setEnvironmentVariable("INFRACOST_TELEMETRY", "false");
    }

    Map<String, Object> normalizePayload(JsonNode root, String apiKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "SUCCESS");
        putTextIfPresent(payload, "version", root.get("version"));
        payload.put("currency", textOrDefault(root.get("currency"), properties.getDefaultCurrency() != null ? properties.getDefaultCurrency() : "USD"));
        putTextIfPresent(payload, "totalMonthlyCost", root.get("totalMonthlyCost"));
        putTextIfPresent(payload, "totalHourlyCost", root.get("totalHourlyCost"));
        putTextIfPresent(payload, "pastTotalMonthlyCost", root.get("pastTotalMonthlyCost"));
        putTextIfPresent(payload, "pastTotalHourlyCost", root.get("pastTotalHourlyCost"));
        putTextIfPresent(payload, "diffTotalMonthlyCost", root.get("diffTotalMonthlyCost"));
        putTextIfPresent(payload, "diffTotalHourlyCost", root.get("diffTotalHourlyCost"));
        putTextIfPresent(payload, "timeGenerated", root.get("timeGenerated"));

        JsonNode shareUrl = root.get("shareUrl");
        if (shareUrl != null && shareUrl.isTextual() && !shareUrl.asText().isBlank()) {
            payload.put("shareUrl", redactSecrets(shareUrl.asText(), apiKey));
        }

        JsonNode summary = root.get("summary");
        if (summary != null && summary.isObject()) {
            payload.put("summary", objectMapper.convertValue(summary, new TypeReference<Map<String, Object>>() {
            }));
        }

        JsonNode projects = root.get("projects");
        if (projects != null && projects.isArray()) {
            payload.put("projects", objectMapper.convertValue(projects, new TypeReference<List<Map<String, Object>>>() {
            }));
        } else {
            payload.put("projects", List.of());
        }

        return payload;
    }

    private InfracostResult recordFailure(TerraformJob job, File workDir, String rawMessage, String apiKey, Throwable cause) {
        String safeMessage = truncate(redactSecrets(rawMessage != null ? rawMessage : "Infracost estimation failed", apiKey));
        if (cause != null) {
            log.warn("Infracost fail-open for job {}: {}", job.getJobId(), safeMessage, cause);
        } else {
            log.warn("Infracost fail-open for job {}: {}", job.getJobId(), safeMessage);
        }

        Map<String, Object> errorPayload = new LinkedHashMap<>();
        errorPayload.put("status", "ERROR");
        errorPayload.put("errorMessage", safeMessage);

        persistToJobContext(job, errorPayload);
        File opaDataFile = writeOpaDataFile(workDir, errorPayload);

        return InfracostResult.builder()
                .status(InfracostResult.Status.ERROR)
                .normalizedContext(Collections.unmodifiableMap(errorPayload))
                .infracostOpaDataFile(opaDataFile)
                .errorMessage(safeMessage)
                .build();
    }

    private void persistToJobContext(TerraformJob job, Map<String, Object> infracostPayload) {
        if (jobContextService == null || job.getOrganizationId() == null || job.getJobId() == null) {
            return;
        }
        try {
            jobContextService.patchContextFresh(
                    job.getOrganizationId(),
                    job.getJobId(),
                    Map.of("infracost", infracostPayload)
            );
        } catch (Exception e) {
            log.warn("Failed to persist Infracost context for job {}: {}", job.getJobId(), e.getMessage());
        }
    }

    private File writeOpaDataFile(File workDir, Map<String, Object> infracostPayload) {
        try {
            FileUtils.forceMkdir(workDir);
            File opaDataFile = new File(workDir, INFRACOST_OPA_DATA_FILE);
            String json = objectMapper.writeValueAsString(Map.of("infracost", infracostPayload));
            FileUtils.writeStringToFile(opaDataFile, json, StandardCharsets.UTF_8);
            return opaDataFile;
        } catch (IOException e) {
            log.warn("Unable to write Infracost OPA data file: {}", e.getMessage());
            return null;
        }
    }

    private File resolveOptionalWorkspaceFile(File workingDirectory, String configuredPath, String defaultFilename) throws IOException {
        boolean explicit = configuredPath != null && !configuredPath.isBlank();
        String relativePath = explicit ? configuredPath.trim() : defaultFilename;
        File candidate = new File(relativePath).isAbsolute()
                ? new File(relativePath)
                : new File(workingDirectory, relativePath);

        String baseCanonical = workingDirectory.getCanonicalPath();
        String candidateCanonical = candidate.getCanonicalPath();
        if (!candidateCanonical.equals(baseCanonical) && !candidateCanonical.startsWith(baseCanonical + File.separator)) {
            throw new SecurityException("Infracost file path escapes workspace directory: " + relativePath);
        }

        if (candidate.isFile()) {
            return candidate;
        }
        if (explicit) {
            log.warn("Configured Infracost file '{}' does not exist inside workspace", relativePath);
        }
        return null;
    }

    private String resolveSetting(TerraformJob job, String key) {
        if (job != null && job.getEnvironmentVariables() != null) {
            String jobValue = job.getEnvironmentVariables().get(key);
            if (jobValue != null && !jobValue.isBlank()) {
                return jobValue;
            }
        }
        return systemEnvLookup.apply(key);
    }

    private static String redactSecrets(String text, String apiKey) {
        if (text == null) {
            return "";
        }
        String sanitized = text;
        if (apiKey != null && !apiKey.isBlank()) {
            sanitized = sanitized.replace(apiKey, "[REDACTED]");
        }
        return INFRACOST_TOKEN_PATTERN.matcher(sanitized).replaceAll("[REDACTED]");
    }

    private static void appendBounded(StringBuilder buffer, String line) {
        if (buffer.length() < 4096 && line != null) {
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(line);
        }
    }

    private static String truncate(String text) {
        return text.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? text
                : text.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "...";
    }

    private static void putTextIfPresent(Map<String, Object> target, String key, JsonNode node) {
        if (node != null && !node.isNull()) {
            target.put(key, node.asText());
        }
    }

    private static String textOrDefault(JsonNode node, String fallback) {
        return (node != null && node.isTextual() && !node.asText().isBlank()) ? node.asText() : fallback;
    }

    private static InfracostResult skipped() {
        return InfracostResult.builder()
                .status(InfracostResult.Status.SKIPPED)
                .build();
    }
}
