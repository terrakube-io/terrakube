package io.terrakube.executor.service.infracost;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.configuration.InfracostExecutorProperties;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.scripts.bash.ProcessLauncher;
import io.terrakube.executor.service.terraform.JobContextService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

@Slf4j
@Service
public class InfracostExecutorServiceImpl implements InfracostExecutorService {

    private static final String CONTEXT_KEY_PREFIX = "infracost";
    private static final int DEFAULT_CONTEXT_MAX_BYTES = 49152;
    private static final int DEFAULT_MAX_CONTEXT_RESOURCES = 50;
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

    @PreDestroy
    void shutdown() {
        processExecutor.shutdownNow();
    }

    @Override
    public InfracostResult evaluateCost(
            TerraformJob job,
            File workingDirectory,
            File planJsonFile,
            Consumer<String> planOutput) {
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
            return recordFailure(job, workDir, "Unable to initialize Infracost working directory", apiKey, e, planOutput);
        }

        try {
            boolean hasPlanJson = planJsonFile != null && planJsonFile.isFile();
            String configuredConfigFile = resolveSetting(job, "INFRACOST_CONFIG_FILE");
            File configFile = null;
            if (!hasPlanJson && configuredConfigFile != null && !configuredConfigFile.isBlank()) {
                configFile = resolveOptionalWorkspaceFile(workingDirectory, configuredConfigFile, null);
            } else if (hasPlanJson && configuredConfigFile != null && !configuredConfigFile.isBlank()) {
                log.info("Ignoring INFRACOST_CONFIG_FILE for job {} because plan.json is present", job.getJobId());
            }
            File usageFile = resolveOptionalWorkspaceFile(workingDirectory, resolveSetting(job, "INFRACOST_USAGE_FILE"), "infracost-usage.yml");

            if (configFile == null && !hasPlanJson) {
                return recordFailure(job, workDir, "Plan JSON missing; cost estimation skipped.", apiKey, null, planOutput);
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
                        apiKey, te, planOutput);
            }

            if (exitCode != 0 || !outputFile.isFile() || outputFile.length() == 0) {
                String details = stderrBuf.length() > 0 ? stderrBuf.toString().trim() : stdoutBuf.toString().trim();
                String message = details.isBlank()
                        ? String.format("Infracost CLI exited with code %d. Plan proceeded without cost estimation.", exitCode)
                        : String.format("Infracost CLI failed (exit %d): %s", exitCode, details);
                return recordFailure(job, workDir, message, apiKey, null, planOutput);
            }

            JsonNode root = objectMapper.readTree(outputFile);
            Map<String, Object> normalized = normalizePayload(root, apiKey);

            persistToJobContext(job, buildBoundedContextPayload(normalized));
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
            return recordFailure(job, workDir, "Infracost estimation was interrupted. Plan proceeded without cost estimation.", apiKey, ie, planOutput);
        } catch (Exception e) {
            return recordFailure(job, workDir,
                    "Infracost estimation encountered an error: " + e.getMessage(),
                    apiKey, e, planOutput);
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

        String endpoint = properties.getPricingApiEndpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            launcher.setEnvironmentVariable("INFRACOST_PRICING_API_ENDPOINT", endpoint.trim());
        }

        launcher.setEnvironmentVariable("INFRACOST_ENABLE_DASHBOARD",
                properties.isEnableDashboard() ? "true" : "false");
        launcher.setEnvironmentVariable("INFRACOST_SKIP_UPDATE_CHECK", "true");
        launcher.setEnvironmentVariable("INFRACOST_TELEMETRY", "false");
    }

    Map<String, Object> normalizePayload(JsonNode root, String apiKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "SUCCESS");
        putTextIfPresent(payload, "version", root.get("version"));
        payload.put("currency", textOrDefault(root.get("currency"), properties.getDefaultCurrency() != null ? properties.getDefaultCurrency() : "USD"));
        putDecimalIfPresent(payload, "totalMonthlyCost", root.get("totalMonthlyCost"));
        putDecimalIfPresent(payload, "totalHourlyCost", root.get("totalHourlyCost"));
        putDecimalIfPresent(payload, "pastTotalMonthlyCost", root.get("pastTotalMonthlyCost"));
        putDecimalIfPresent(payload, "pastTotalHourlyCost", root.get("pastTotalHourlyCost"));
        putDecimalIfPresent(payload, "diffTotalMonthlyCost", root.get("diffTotalMonthlyCost"));
        putDecimalIfPresent(payload, "diffTotalHourlyCost", root.get("diffTotalHourlyCost"));
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

    private InfracostResult recordFailure(
            TerraformJob job,
            File workDir,
            String rawMessage,
            String apiKey,
            Throwable cause,
            Consumer<String> planOutput) {
        String safeMessage = truncate(redactSecrets(rawMessage != null ? rawMessage : "Infracost estimation failed", apiKey));
        if (cause != null) {
            log.warn("Infracost fail-open for job {}: {}", job.getJobId(), safeMessage, cause);
        } else {
            log.warn("Infracost fail-open for job {}: {}", job.getJobId(), safeMessage);
        }
        if (planOutput != null) {
            planOutput.accept("[WARN] Infracost cost estimation failed (fail-open): " + safeMessage);
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
        String contextKey = (job.getStepId() != null && !job.getStepId().isBlank())
                ? CONTEXT_KEY_PREFIX + "/" + job.getStepId()
                : CONTEXT_KEY_PREFIX;
        try {
            jobContextService.patchContextFresh(
                    job.getOrganizationId(),
                    job.getJobId(),
                    Map.of(contextKey, infracostPayload)
            );
        } catch (Exception e) {
            log.warn("Failed to persist Infracost context ({}) for job {}: {}", contextKey, job.getJobId(), e.getMessage());
        }
    }

    Map<String, Object> buildBoundedContextPayload(Map<String, Object> fullPayload) {
        int maxBytes = properties.getContextMaxBytes() > 0 ? properties.getContextMaxBytes() : DEFAULT_CONTEXT_MAX_BYTES;
        int maxResources = properties.getMaxContextResources() > 0 ? properties.getMaxContextResources() : DEFAULT_MAX_CONTEXT_RESOURCES;

        int totalResources = countDistinctProjectResources(fullPayload);
        byte[] fullBytes = serializeUtf8(fullPayload);
        if (fullBytes != null && fullBytes.length <= maxBytes && totalResources <= maxResources) {
            return fullPayload;
        }

        Map<String, Object> trimmed = copyHeaderFields(fullPayload);
        List<Map<String, Object>> trimmedProjects = trimProjectsToTopResources(fullPayload, maxResources);
        trimmed.put("projects", trimmedProjects);
        int keptResources = countDistinctProjectResources(trimmed);
        int omitted = Math.max(0, totalResources - keptResources);
        trimmed.put("truncated", true);
        trimmed.put("omittedResourceCount", omitted);

        byte[] trimmedBytes = serializeUtf8(trimmed);
        if (trimmedBytes != null && trimmedBytes.length <= maxBytes) {
            return trimmed;
        }

        // Still above byte cap: drop project resource breakdowns, keeping totals + summary.
        Map<String, Object> summaryOnly = copyHeaderFields(fullPayload);
        summaryOnly.put("projects", List.of());
        summaryOnly.put("truncated", true);
        summaryOnly.put("omittedResourceCount", totalResources);

        byte[] summaryBytes = serializeUtf8(summaryOnly);
        if (summaryBytes != null && summaryBytes.length <= maxBytes) {
            return summaryOnly;
        }

        // Last resort: drop verbose summary counts too so the context patch fits comfortably.
        Map<String, Object> minimal = copyHeaderFields(fullPayload);
        minimal.remove("summary");
        minimal.put("projects", List.of());
        minimal.put("truncated", true);
        minimal.put("omittedResourceCount", totalResources);
        return minimal;
    }

    private static Map<String, Object> copyHeaderFields(Map<String, Object> fullPayload) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (String key : List.of(
                "status", "version", "currency",
                "totalMonthlyCost", "totalHourlyCost",
                "pastTotalMonthlyCost", "pastTotalHourlyCost",
                "diffTotalMonthlyCost", "diffTotalHourlyCost",
                "timeGenerated", "shareUrl", "summary")) {
            if (fullPayload.containsKey(key)) {
                copy.put(key, fullPayload.get(key));
            }
        }
        return copy;
    }

    private List<Map<String, Object>> trimProjectsToTopResources(Map<String, Object> fullPayload, int maxResources) {
        List<Map<String, Object>> projects = asMapList(fullPayload.get("projects"));
        if (projects.isEmpty()) {
            return List.of();
        }

        record RankedResource(int projectIndex, String name, BigDecimal rankCost) {
        }

        List<RankedResource> ranked = new ArrayList<>();
        for (int pIdx = 0; pIdx < projects.size(); pIdx++) {
            Map<String, Object> project = projects.get(pIdx);
            Map<String, BigDecimal> maxImpactByName = new LinkedHashMap<>();
            collectResourceWeights(asSectionResources(project.get("diff")), maxImpactByName);
            collectResourceWeights(asSectionResources(project.get("breakdown")), maxImpactByName);
            collectResourceWeights(asSectionResources(project.get("pastBreakdown")), maxImpactByName);
            for (Map.Entry<String, BigDecimal> entry : maxImpactByName.entrySet()) {
                ranked.add(new RankedResource(pIdx, entry.getKey(), entry.getValue()));
            }
        }

        ranked.sort(Comparator.comparing(RankedResource::rankCost, Comparator.reverseOrder())
                .thenComparingInt(RankedResource::projectIndex)
                .thenComparing(RankedResource::name));

        Map<Integer, Set<String>> keepByProject = new HashMap<>();
        int limit = Math.min(maxResources, ranked.size());
        for (int i = 0; i < limit; i++) {
            RankedResource item = ranked.get(i);
            keepByProject.computeIfAbsent(item.projectIndex(), k -> new HashSet<>()).add(item.name());
        }

        List<Map<String, Object>> result = new ArrayList<>(projects.size());
        for (int pIdx = 0; pIdx < projects.size(); pIdx++) {
            Map<String, Object> project = projects.get(pIdx);
            Set<String> kept = keepByProject.getOrDefault(pIdx, Collections.emptySet());
            Map<String, Object> trimmedProject = new LinkedHashMap<>();
            if (project.containsKey("name")) {
                trimmedProject.put("name", project.get("name"));
            }
            for (String sectionKey : List.of("pastBreakdown", "breakdown", "diff")) {
                Object sectionObj = project.get(sectionKey);
                if (sectionObj instanceof Map<?, ?> sectionMap) {
                    Map<String, Object> copySection = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : sectionMap.entrySet()) {
                        String key = String.valueOf(e.getKey());
                        if ("resources".equals(key)) {
                            copySection.put("resources", filterAndStripResources(asMapList(e.getValue()), kept));
                        } else {
                            copySection.put(key, e.getValue());
                        }
                    }
                    trimmedProject.put(sectionKey, copySection);
                }
            }
            result.add(trimmedProject);
        }
        return result;
    }

    private static void collectResourceWeights(List<Map<String, Object>> resources, Map<String, BigDecimal> maxByName) {
        for (Map<String, Object> resource : resources) {
            Object nameObj = resource.get("name");
            if (nameObj == null) {
                continue;
            }
            String name = String.valueOf(nameObj);
            BigDecimal weight = parseDecimal(resource.get("monthlyCost")).abs();
            maxByName.merge(name, weight, BigDecimal::max);
        }
    }

    private static List<Map<String, Object>> filterAndStripResources(List<Map<String, Object>> resources, Set<String> keptNames) {
        if (resources.isEmpty() || keptNames.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> resource : resources) {
            Object nameObj = resource.get("name");
            if (nameObj != null && keptNames.contains(String.valueOf(nameObj))) {
                Map<String, Object> copy = new LinkedHashMap<>(resource);
                copy.remove("metadata");
                filtered.add(copy);
            }
        }
        return filtered;
    }

    private static int countDistinctProjectResources(Map<String, Object> payload) {
        List<Map<String, Object>> projects = asMapList(payload.get("projects"));
        int total = 0;
        for (Map<String, Object> project : projects) {
            Set<String> names = new HashSet<>();
            for (String sectionKey : List.of("breakdown", "pastBreakdown", "diff")) {
                for (Map<String, Object> resource : asSectionResources(project.get(sectionKey))) {
                    Object nameObj = resource.get("name");
                    if (nameObj != null) {
                        names.add(String.valueOf(nameObj));
                    }
                }
            }
            total += names.size();
        }
        return total;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    private static List<Map<String, Object>> asSectionResources(Object section) {
        if (section instanceof Map<?, ?> map) {
            return asMapList(map.get("resources"));
        }
        return List.of();
    }

    private byte[] serializeUtf8(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal parseDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
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
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
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

    private static void putDecimalIfPresent(Map<String, Object> target, String key, JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isNumber()) {
            target.put(key, node.decimalValue());
            return;
        }
        if (node.isTextual()) {
            String raw = node.asText().trim();
            if (!raw.isEmpty()) {
                try {
                    target.put(key, new BigDecimal(raw));
                } catch (NumberFormatException e) {
                    log.debug("Ignoring non-numeric cost field {}={}", key, raw);
                }
            }
        }
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
