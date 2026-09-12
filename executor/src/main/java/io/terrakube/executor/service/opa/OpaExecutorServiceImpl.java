package io.terrakube.executor.service.opa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.executor.ExecutorJobResult;
import io.terrakube.executor.service.mode.PolicyContext;
import io.terrakube.executor.service.mode.PolicyExemptionContext;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.opa.model.OpaEvaluationResult;
import io.terrakube.executor.service.opa.model.PolicyViolation;
import io.terrakube.executor.service.opa.model.ViolationStatus;
import io.terrakube.executor.service.terraform.JobContextService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.text.TextStringBuilder;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OpaExecutorServiceImpl implements OpaExecutorService {

    private static final String HARD_MANDATORY = "HARD_MANDATORY";
    private static final String SOFT_MANDATORY = "SOFT_MANDATORY";
    private static final String ADVISORY = "ADVISORY";

    private static final String STATUS_PASSED = "PASSED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_WAITING_APPROVAL = "WAITING_APPROVAL";
    private static final String STATUS_WARNING = "WARNING";

    private final OpaBinaryService opaBinaryService;
    private final ThreadPoolTaskExecutor opaEvaluationExecutor;
    private final ObjectMapper objectMapper;
    private final JobContextService jobContextService;
    private final TerraformState terraformState;

    @Autowired
    public OpaExecutorServiceImpl(
            OpaBinaryService opaBinaryService,
            @Qualifier("opaEvaluationExecutor") ThreadPoolTaskExecutor opaEvaluationExecutor,
            ObjectMapper objectMapper,
            JobContextService jobContextService,
            TerraformState terraformState) {
        this.opaBinaryService = opaBinaryService;
        this.opaEvaluationExecutor = opaEvaluationExecutor;
        this.objectMapper = objectMapper;
        this.jobContextService = jobContextService;
        this.terraformState = terraformState;
    }

    @Override
    public List<OpaEvaluationResult> evaluateAllPolicies(
            TerraformJob job,
            File workingDirectory,
            File planJsonFile,
            Consumer<String> planOutput) {

        if (job.getPolicyList() == null || job.getPolicyList().isEmpty()) {
            log.info("No policy sets configured for job {}", job.getJobId());
            return Collections.emptyList();
        }

        log.info("Evaluating {} policy set(s) for job {}", job.getPolicyList().size(), job.getJobId());

        List<PolicyExemptionContext> exemptions = job.getPolicyExemptionList() != null
                ? job.getPolicyExemptionList()
                : Collections.emptyList();

        List<CompletableFuture<OpaEvaluationResult>> futures = new ArrayList<>();

        for (PolicyContext policyContext : job.getPolicyList()) {
            CompletableFuture<OpaEvaluationResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    File policyBundleDir = resolvePolicyBundleDirectory(workingDirectory, policyContext);
                    File policyInputsFile = preparePolicyInputsFile(workingDirectory, policyContext);
                    return evaluatePolicySet(
                            policyContext,
                            exemptions,
                            workingDirectory,
                            planJsonFile,
                            policyBundleDir,
                            policyInputsFile,
                            null
                    );
                } catch (Exception e) {
                    log.error("Error evaluating policy set {}: {}", policyContext.getPolicyName(), e.getMessage(), e);
                    OpaEvaluationResult errorResult = OpaEvaluationResult.builder()
                            .policySetId(policyContext.getPolicyId())
                            .policySetName(policyContext.getPolicyName())
                            .enforcementLevel(policyContext.getEnforcementLevel())
                            .shadowEnforcementLevel(policyContext.getShadowEnforcementLevel())
                            .status(STATUS_FAILED)
                            .hardMandatoryViolations(1)
                            .exitCode(1)
                            .build();
                    errorResult.getBufferedLogs().add(String.format(
                            "\u001B[31m[ERROR]\u001B[0m Failed to execute policy set %s: %s",
                            policyContext.getPolicyName(), e.getMessage()
                    ));
                    return errorResult;
                }
            }, opaEvaluationExecutor);
            futures.add(future);
        }

        // Wait for all evaluations to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<OpaEvaluationResult> results = futures.stream()
                .map(CompletableFuture::join)
                .sorted(Comparator.comparing(r -> r.getPolicySetName() != null ? r.getPolicySetName() : ""))
                .collect(Collectors.toList());

        // Sequential Log Flushing to avoid terminal interleaving
        planOutput.accept("\n\u001B[1;36m============================================================\u001B[0m");
        planOutput.accept("\u001B[1;36m       TERRAKUBE OPEN POLICY AGENT (OPA) GOVERNANCE        \u001B[0m");
        planOutput.accept("\u001B[1;36m============================================================\u001B[0m\n");

        int totalHardViolations = 0;
        int totalSoftViolations = 0;
        int totalShadowHardViolations = 0;
        int totalShadowSoftViolations = 0;
        int totalExempted = 0;

        for (OpaEvaluationResult result : results) {
            totalHardViolations += result.getHardMandatoryViolations();
            totalSoftViolations += result.getSoftMandatoryViolations();
            totalShadowHardViolations += result.getShadowHardViolations();
            totalShadowSoftViolations += result.getShadowSoftViolations();
            totalExempted += result.getExemptedViolations().size();

            for (String logLine : result.getBufferedLogs()) {
                planOutput.accept(logLine);
            }
            planOutput.accept(""); // Blank separator between policy sets
        }

        // Summary Banner
        planOutput.accept("\u001B[1;36m------------------------------------------------------------\u001B[0m");
        if (totalHardViolations > 0) {
            planOutput.accept(String.format(
                    "\u001B[1;31m⛔ [OPA POLICY FAILURE] %d hard-mandatory violation(s) detected. Plan blocked.\u001B[0m",
                    totalHardViolations
            ));
        } else if (totalSoftViolations > 0) {
            planOutput.accept(String.format(
                    "\u001B[1;33m⚠️ [OPA POLICY OVERRIDE REQUIRED] %d soft-mandatory violation(s) detected. SecOps approval required.\u001B[0m",
                    totalSoftViolations
            ));
        } else {
            planOutput.accept("\u001B[1;32m✅ [OPA POLICY SUCCESS] All policy guardrails passed successfully.\u001B[0m");
        }

        if (totalShadowHardViolations > 0 || totalShadowSoftViolations > 0) {
            planOutput.accept(String.format(
                    "\u001B[35m👻 [SHADOW MODE] Informational telemetry: %d hard and %d soft violations would trigger.\u001B[0m",
                    totalShadowHardViolations, totalShadowSoftViolations
            ));
        }

        if (totalExempted > 0) {
            planOutput.accept(String.format(
                    "\u001B[35m🛡️ [EXEMPTIONS ACTIVE] %d rule violation(s) bypassed via approved active exemptions.\u001B[0m",
                    totalExempted
            ));
        }
        planOutput.accept("\u001B[1;36m============================================================\u001B[0m\n");

        // Save structured results to JobContextService for UI inspection
        saveStructuredContext(job, results);

        return results;
    }

    @Override
    public OpaEvaluationResult evaluatePolicySet(
            PolicyContext policyContext,
            List<PolicyExemptionContext> exemptions,
            File workingDirectory,
            File planJsonFile,
            File policyBundleDir,
            File policyInputsFile,
            Consumer<String> consoleOutput) {

        List<String> logs = new ArrayList<>();
        Consumer<String> logConsumer = consoleOutput != null ? consoleOutput : logs::add;

        String policyName = policyContext.getPolicyName() != null ? policyContext.getPolicyName() : policyContext.getPolicyId();
        String enforcementLevel = policyContext.getEnforcementLevel() != null ? policyContext.getEnforcementLevel() : HARD_MANDATORY;

        logConsumer.accept(String.format("\u001B[1;34m🔍 Checking Policy Set: %s\u001B[0m (Level: %s)", policyName, enforcementLevel));

        File opaBinary = opaBinaryService.getOpaBinary(policyContext.getOpaVersion());

        File libDir = resolveLibDirectory(workingDirectory, policyBundleDir, policyContext);

        List<String> commandList = new ArrayList<>();
        commandList.add(opaBinary.getAbsolutePath());
        commandList.add("eval");
        commandList.add("--data");
        commandList.add(policyBundleDir.getAbsolutePath());
        if (libDir != null && libDir.exists() && !libDir.equals(policyBundleDir)) {
            commandList.add("--data");
            commandList.add(libDir.getAbsolutePath());
        }
        if (policyInputsFile != null && policyInputsFile.exists()) {
            commandList.add("--data");
            commandList.add(policyInputsFile.getAbsolutePath());
        }
        commandList.add("--input");
        commandList.add(planJsonFile.getAbsolutePath());
        commandList.add("--format");
        commandList.add("json");
        commandList.add("data.terraform.analysis");

        TextStringBuilder rawJsonOutput = new TextStringBuilder();
        TextStringBuilder stderrOutput = new TextStringBuilder();

        int exitCode;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(commandList);
            if (workingDirectory != null) {
                processBuilder.directory(workingDirectory);
            }
            Process process = processBuilder.start();

            Thread stdoutThread = Thread.ofVirtual()
                    .name("opa-stdout-" + policyContext.getPolicyId())
                    .start(() -> {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                rawJsonOutput.appendln(line);
                            }
                        } catch (IOException e) {
                            log.debug("Error reading OPA stdout for {}: {}", policyName, e.getMessage());
                        }
                    });

            Thread stderrThread = Thread.ofVirtual()
                    .name("opa-stderr-" + policyContext.getPolicyId())
                    .start(() -> {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                stderrOutput.appendln(line);
                            }
                        } catch (IOException e) {
                            log.debug("Error reading OPA stderr for {}: {}", policyName, e.getMessage());
                        }
                    });

            boolean completed = process.waitFor(120, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new TimeoutException("OPA evaluation timed out after 120 seconds");
            }
            stdoutThread.join(TimeUnit.SECONDS.toMillis(5));
            stderrThread.join(TimeUnit.SECONDS.toMillis(5));
            exitCode = process.exitValue();
        } catch (Exception e) {
            log.error("Failed to execute opa process: {}", e.getMessage(), e);
            OpaEvaluationResult errorResult = OpaEvaluationResult.builder()
                    .policySetId(policyContext.getPolicyId())
                    .policySetName(policyName)
                    .enforcementLevel(enforcementLevel)
                    .status(STATUS_FAILED)
                    .exitCode(1)
                    .build();
            errorResult.getBufferedLogs().add("\u001B[31m[ERROR] Failed to run OPA binary: " + e.getMessage() + "\u001B[0m");
            return errorResult;
        }

        if (exitCode != 0 && stderrOutput.length() > 0) {
            log.warn("OPA evaluation stderr for {}: {}", policyName, stderrOutput);
            logConsumer.accept(String.format("  \u001B[31m[ERROR]\u001B[0m %s", stderrOutput.toString().trim()));
        }

        OpaEvaluationResult result = parseOpaJsonOutput(policyContext, rawJsonOutput.toString(), exitCode, logConsumer);

        // Apply Runner-Level Exemption Filtering
        applyExemptions(result, policyContext.getPolicyId(), exemptions, logConsumer);

        // Finalize status and exit code
        finalizeResultStatus(result, enforcementLevel, policyContext.getShadowEnforcementLevel(), logConsumer);

        result.setBufferedLogs(logs);
        return result;
    }

    @Override
    public ExecutorJobResult evaluateJob(TerraformJob job, File workingDirectory) {
        log.info("Starting headless policy evaluation for Organization {} Workspace {} Job {}",
                job.getOrganizationId(), job.getWorkspaceId(), job.getJobId());

        ExecutorJobResult result = new ExecutorJobResult();
        TextStringBuilder outputLog = new TextStringBuilder();
        Consumer<String> planOutput = outputLog::appendln;

        // Step 1: Resolve plan.json without full terraform init
        File planJsonFile = new File(workingDirectory, "plan.json");
        if (!planJsonFile.exists()) {
            // Attempt to restore plan from storage
            boolean downloaded = terraformState.downloadTerraformPlan(
                    job.getOrganizationId(), job.getWorkspaceId(), job.getJobId(), job.getStepId(), workingDirectory
            );
            if (!downloaded) {
                log.warn("Could not find plan.json or download terraform plan file from storage");
                result.setSuccessfulExecution(false);
                result.setOutputLog("No plan.json or state found for compliance evaluation.");
                result.setOutputErrorLog("Failed to download plan for headless policyEvaluation.");
                result.setExitCode(1);
                return result;
            }
        }

        // Step 2: Evaluate all policies
        List<OpaEvaluationResult> evalResults = evaluateAllPolicies(job, workingDirectory, planJsonFile, planOutput);

        boolean hasHardViolations = evalResults.stream().anyMatch(r -> r.getHardMandatoryViolations() > 0);
        result.setOutputLog(outputLog.toString());
        result.setSuccessfulExecution(!hasHardViolations);
        result.setExitCode(hasHardViolations ? 1 : 0);
        result.setPlan(false);

        return result;
    }

    private File resolvePolicyBundleDirectory(File workingDirectory, PolicyContext policyContext) throws Exception {
        if (policyContext.getRepository() == null || policyContext.getRepository().isBlank()) {
            // If no VCS repository specified, default to working directory folder
            File resolvedNoVcs = new File(workingDirectory, policyContext.getFolder() != null ? policyContext.getFolder() : "").getCanonicalFile();
            validatePathBoundary(resolvedNoVcs, workingDirectory, policyContext.getFolder());
            return resolvedNoVcs;
        }

        File policyCloneFolder = new File(workingDirectory, ".terrakube-policies/" + policyContext.getPolicyId());
        if (policyCloneFolder.exists()) {
            File resolvedExisting = new File(policyCloneFolder, policyContext.getFolder() != null ? policyContext.getFolder() : "").getCanonicalFile();
            validatePathBoundary(resolvedExisting, policyCloneFolder, policyContext.getFolder());
            return resolvedExisting;
        }

        // Clone into a temp directory first; rename atomically once successful so that a
        // failed/interrupted clone never leaves a stale partial directory that would be
        // mistaken for a valid bundle on the next evaluation run (Issue 2.2).
        File tempCloneFolder = new File(workingDirectory, ".terrakube-policies/tmp-" + policyContext.getPolicyId());
        if (tempCloneFolder.exists()) {
            log.warn("Removing stale temp clone directory before re-cloning: {}", tempCloneFolder.getAbsolutePath());
            FileUtils.deleteDirectory(tempCloneFolder);
        }
        FileUtils.forceMkdirParent(tempCloneFolder);

        CredentialsProvider credentialsProvider = resolveCredentialsProvider(policyContext);

        try {
            CloneCommand cloneCommand = Git.cloneRepository()
                    .setURI(policyContext.getRepository())
                    .setDirectory(tempCloneFolder)
                    .setBranch(policyContext.getBranch() != null ? policyContext.getBranch() : "main")
                    .setCredentialsProvider(credentialsProvider)
                    .setDepth(1);

            cloneCommand.call().close();
        } catch (Exception e) {
            // Clean up the partial clone so that a retry starts from scratch.
            log.error("Clone of policy repository '{}' failed; removing temp directory '{}': {}",
                    policyContext.getRepository(), tempCloneFolder.getAbsolutePath(), e.getMessage());
            FileUtils.deleteQuietly(tempCloneFolder);
            throw e;
        }

        // Atomic rename: temp → final directory.
        try {
            try {
                Files.move(tempCloneFolder.toPath(), policyCloneFolder.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tempCloneFolder.toPath(), policyCloneFolder.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.error("Failed to promote temp policy clone directory '{}' to '{}': {}",
                    tempCloneFolder.getAbsolutePath(), policyCloneFolder.getAbsolutePath(), e.getMessage());
            FileUtils.deleteQuietly(tempCloneFolder);
            FileUtils.deleteQuietly(policyCloneFolder);
            throw e;
        }
        log.info("Policy bundle cloned and promoted to '{}' for policy set '{}'.",
                policyCloneFolder.getAbsolutePath(), policyContext.getPolicyId());

        File resolvedAfterClone = new File(policyCloneFolder, policyContext.getFolder() != null ? policyContext.getFolder() : "").getCanonicalFile();
        validatePathBoundary(resolvedAfterClone, policyCloneFolder, policyContext.getFolder());
        return resolvedAfterClone;
    }

    /**
     * Validates that a resolved path does not escape the expected parent boundary.
     * Prevents path traversal attacks where a crafted policy 'folder' value (e.g. "../../..") could
     * cause OPA to read arbitrary host filesystem directories via the --data argument.
     *
     * @param resolved   the canonical resolved path to validate
     * @param boundary   the directory within which the resolved path must remain
     * @param rawFolder  the original raw folder value from the PolicyContext (used in error messages)
     * @throws SecurityException if the resolved path escapes the boundary directory
     */
    private void validatePathBoundary(File resolved, File boundary, String rawFolder) throws Exception {
        java.nio.file.Path resolvedPath = resolved.toPath();
        java.nio.file.Path boundaryPath = boundary.getCanonicalFile().toPath();
        if (!resolvedPath.startsWith(boundaryPath)) {
            log.error("Policy folder '{}' resolves to '{}' which is outside the allowed boundary '{}'",
                    rawFolder, resolvedPath, boundaryPath);
            throw new SecurityException(
                    "Policy folder path traverses outside the allowed bundle directory. Folder value: " + rawFolder);
        }
    }

    File resolveLibDirectory(File workingDirectory, File policyBundleDir, PolicyContext policyContext) {
        if (policyContext != null && policyContext.getRepository() != null && !policyContext.getRepository().isBlank() && workingDirectory != null) {
            File policyCloneFolder = new File(workingDirectory, ".terrakube-policies/" + policyContext.getPolicyId());
            File repoLib = new File(policyCloneFolder, "lib");
            if (repoLib.exists() && repoLib.isDirectory()) {
                return repoLib;
            }
        }

        if (policyBundleDir != null) {
            File current = policyBundleDir.getParentFile();
            while (current != null) {
                File candidate = new File(current, "lib");
                if (candidate.exists() && candidate.isDirectory() && !candidate.equals(policyBundleDir)) {
                    return candidate;
                }
                if (workingDirectory != null && current.equals(workingDirectory)) {
                    break;
                }
                current = current.getParentFile();
            }
        }

        if (workingDirectory != null) {
            File workLib = new File(workingDirectory, "lib");
            if (workLib.exists() && workLib.isDirectory() && !workLib.equals(policyBundleDir)) {
                return workLib;
            }
        }

        return null;
    }

    private CredentialsProvider resolveCredentialsProvider(PolicyContext policyContext) {
        String token = policyContext.getAccessToken();
        if (token == null || token.isBlank()) {
            return null;
        }

        String vcsType = policyContext.getVcsType() != null ? policyContext.getVcsType().toUpperCase() : "";
        switch (vcsType) {
            case "GITLAB":
                return new UsernamePasswordCredentialsProvider("oauth2", token);
            case "BITBUCKET":
                return new UsernamePasswordCredentialsProvider("x-token-auth", token);
            case "AZURE_DEVOPS":
                return new UsernamePasswordCredentialsProvider("dummy", token);
            case "GITHUB":
            default:
                return new UsernamePasswordCredentialsProvider("x-access-token", token);
        }
    }

    File preparePolicyInputsFile(File workingDirectory, PolicyContext policyContext) {
        Map<String, String> inputs = policyContext.getInputs();
        if (inputs == null || inputs.isEmpty()) {
            return null;
        }

        try {
            Map<String, Object> root = new HashMap<>();
            Map<String, Object> terrakube = new HashMap<>();
            Map<String, Object> parsedInputs = new HashMap<>();

            for (Map.Entry<String, String> entry : inputs.entrySet()) {
                String key = entry.getKey();
                String rawVal = entry.getValue();
                if (rawVal != null) {
                    try {
                        JsonNode jsonNode = objectMapper.readTree(rawVal);
                        if (jsonNode.isArray() || jsonNode.isObject() || jsonNode.isNumber() || jsonNode.isBoolean()) {
                            parsedInputs.put(key, objectMapper.treeToValue(jsonNode, Object.class));
                        } else {
                            parsedInputs.put(key, rawVal);
                        }
                    } catch (Exception parseEx) {
                        parsedInputs.put(key, rawVal);
                    }
                }
            }

            terrakube.put("inputs", parsedInputs);
            root.put("terrakube", terrakube);

            File inputsFile = new File(workingDirectory, ".terrakube-policies/inputs-" + policyContext.getPolicyId() + ".json");
            FileUtils.forceMkdirParent(inputsFile);
            objectMapper.writeValue(inputsFile, root);
            return inputsFile;
        } catch (IOException e) {
            log.warn("Failed to write policy inputs JSON file: {}", e.getMessage());
            return null;
        }
    }

    OpaEvaluationResult parseOpaJsonOutput(
            PolicyContext policyContext,
            String rawJson,
            int exitCode,
            Consumer<String> logConsumer) {

        OpaEvaluationResult result = OpaEvaluationResult.builder()
                .policySetId(policyContext.getPolicyId())
                .policySetName(policyContext.getPolicyName())
                .enforcementLevel(policyContext.getEnforcementLevel())
                .shadowEnforcementLevel(policyContext.getShadowEnforcementLevel())
                .build();

        if (rawJson == null || rawJson.isBlank()) {
            if (exitCode != 0) {
                logConsumer.accept(String.format("  \u001B[31m[ERROR]\u001B[0m OPA process exited with code %d and no output.", exitCode));
                result.setStatus(STATUS_FAILED);
                result.setExitCode(exitCode);
                result.getViolations().add(PolicyViolation.builder()
                        .ruleId("opa_process_error")
                        .address(policyContext.getPolicyName() != null ? policyContext.getPolicyName() : policyContext.getPolicyId())
                        .message("OPA process exited with code " + exitCode)
                        .status(ViolationStatus.FAILED)
                        .build());
                return result;
            }
            logConsumer.accept("  \u001B[33mNo output returned from OPA evaluation.\u001B[0m");
            return result;
        }

        try {
            JsonNode rootNode = objectMapper.readTree(rawJson);

            // Check for OPA compilation/evaluation errors in output
            if (rootNode.has("errors") && rootNode.get("errors").isArray() && !rootNode.get("errors").isEmpty()) {
                for (JsonNode err : rootNode.get("errors")) {
                    String msg = err.path("message").asText();
                    String file = err.path("location").path("file").asText("");
                    int row = err.path("location").path("row").asInt(0);
                    String location = (!file.isEmpty() && row > 0) ? String.format(" (%s:%d)", file, row) : "";
                    logConsumer.accept(String.format("  \u001B[31m[ERROR]\u001B[0m OPA error: %s%s", msg, location));
                }
                result.setStatus(STATUS_FAILED);
                result.setExitCode(exitCode != 0 ? exitCode : 1);
                result.getViolations().add(PolicyViolation.builder()
                        .ruleId("opa_compilation_error")
                        .address(policyContext.getPolicyName() != null ? policyContext.getPolicyName() : policyContext.getPolicyId())
                        .message("OPA evaluation failed with compilation/evaluation errors")
                        .status(ViolationStatus.FAILED)
                        .build());
                return result;
            }

            if (exitCode != 0) {
                logConsumer.accept(String.format("  \u001B[31m[ERROR]\u001B[0m OPA process exited with code %d", exitCode));
                result.setStatus(STATUS_FAILED);
                result.setExitCode(exitCode);
                result.getViolations().add(PolicyViolation.builder()
                        .ruleId("opa_process_error")
                        .address(policyContext.getPolicyName() != null ? policyContext.getPolicyName() : policyContext.getPolicyId())
                        .message("OPA process exited with code " + exitCode)
                        .status(ViolationStatus.FAILED)
                        .build());
                return result;
            }

            JsonNode resultNode = rootNode.path("result");

            if (resultNode.isMissingNode() || resultNode.isNull() || (resultNode.isArray() && resultNode.isEmpty())) {
                logConsumer.accept("  \u001B[32m✔ No rule violations reported (All rules passed).\u001B[0m");
                result.setPassedRules(1);
                return result;
            }

            JsonNode valueNode = null;
            if (resultNode.isArray() && !resultNode.isEmpty()) {
                JsonNode firstElem = resultNode.get(0);
                JsonNode expressions = firstElem.path("expressions");
                if (expressions.isArray() && !expressions.isEmpty()) {
                    valueNode = expressions.get(0).path("value");
                }
            } else if (resultNode.isObject()) {
                valueNode = resultNode;
            }

            if (valueNode == null || valueNode.isMissingNode()) {
                logConsumer.accept("  \u001B[32m✔ All policy rules passed.\u001B[0m");
                result.setPassedRules(1);
                return result;
            }

            // Extract deny violations
            List<PolicyViolation> denyList = extractViolationsFromNode(valueNode.path("deny"));
            // Extract soft_mandatory violations
            List<PolicyViolation> softList = extractViolationsFromNode(valueNode.path("soft_mandatory"));
            // Extract warning violations
            List<PolicyViolation> warnList = extractViolationsFromNode(valueNode.path("warn"));

            for (PolicyViolation v : warnList) {
                v.setStatus(ViolationStatus.WARNING);
            }

            result.getViolations().addAll(denyList);
            result.getViolations().addAll(softList);
            result.getViolations().addAll(warnList);
            result.setWarningRules(warnList.size());

            // Render violation notices to log
            for (PolicyViolation v : denyList) {
                logConsumer.accept(String.format(
                        "  \u001B[31m[DENY]\u001B[0m Rule '%s' failed on '%s': %s",
                        v.getRuleId(), v.getAddress(), v.getMessage()
                ));
            }

            for (PolicyViolation v : softList) {
                logConsumer.accept(String.format(
                        "  \u001B[33m[SOFT_MANDATORY]\u001B[0m Rule '%s' flagged on '%s': %s",
                        v.getRuleId(), v.getAddress(), v.getMessage()
                ));
            }

            for (PolicyViolation v : warnList) {
                logConsumer.accept(String.format(
                        "  \u001B[36m[WARN]\u001B[0m Rule '%s' on '%s': %s",
                        v.getRuleId(), v.getAddress(), v.getMessage()
                ));
            }

            if (denyList.isEmpty() && softList.isEmpty() && warnList.isEmpty()) {
                logConsumer.accept("  \u001B[32m✔ All policy rules passed.\u001B[0m");
                result.setPassedRules(1);
            }

        } catch (Exception e) {
            log.warn("Failed to parse OPA JSON output: {}", e.getMessage());
            logConsumer.accept("  \u001B[31m[ERROR]\u001B[0m Output could not be parsed as structured OPA result: " + e.getMessage());
            if (exitCode != 0) {
                result.setStatus(STATUS_FAILED);
                result.setExitCode(exitCode);
                result.getViolations().add(PolicyViolation.builder()
                        .ruleId("opa_parse_error")
                        .address(policyContext.getPolicyName() != null ? policyContext.getPolicyName() : policyContext.getPolicyId())
                        .message("Failed to parse OPA output: " + e.getMessage())
                        .status(ViolationStatus.FAILED)
                        .build());
            }
        }

        return result;
    }

    private List<PolicyViolation> extractViolationsFromNode(JsonNode node) {
        List<PolicyViolation> violations = new ArrayList<>();
        if (node.isMissingNode() || node.isNull()) {
            return violations;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                violations.add(parseViolationItem(item));
            }
        } else if (node.isObject()) {
            violations.add(parseViolationItem(node));
        }
        return violations;
    }

    private PolicyViolation parseViolationItem(JsonNode item) {
        if (item.isTextual()) {
            return PolicyViolation.builder()
                    .ruleId("policy_rule")
                    .address("global")
                    .message(item.asText())
                    .status(ViolationStatus.FAILED)
                    .build();
        }

        String msg = item.has("msg") ? item.path("msg").asText() : (item.has("message") ? item.path("message").asText() : item.toString());
        String ruleId = item.has("rule_id") ? item.path("rule_id").asText() : (item.has("ruleId") ? item.path("ruleId").asText() : "policy_rule");
        String address = item.has("resource") ? item.path("resource").asText() : (item.has("address") ? item.path("address").asText() : "resource");

        return PolicyViolation.builder()
                .ruleId(ruleId)
                .address(address)
                .message(msg)
                .status(ViolationStatus.FAILED)
                .build();
    }

    void applyExemptions(
            OpaEvaluationResult result,
            String policySetId,
            List<PolicyExemptionContext> exemptions,
            Consumer<String> logConsumer) {

        Date now = new Date();
        Iterator<PolicyViolation> iterator = result.getViolations().iterator();

        while (iterator.hasNext()) {
            PolicyViolation violation = iterator.next();
            Optional<PolicyExemptionContext> matchingExemption = exemptions.stream()
                    .filter(e -> e.getPolicySetId() != null && e.getPolicySetId().equals(policySetId))
                    .filter(e -> e.getRuleId() != null && e.getRuleId().equals(violation.getRuleId()))
                    .filter(e -> e.getExpiresAt() == null || e.getExpiresAt().after(now))
                    .findFirst();

            if (matchingExemption.isPresent()) {
                PolicyExemptionContext ex = matchingExemption.get();
                iterator.remove(); // Remove from blocking violations

                violation.setStatus(ViolationStatus.EXEMPTED);
                violation.setTicketReference(ex.getTicketReference());
                violation.setJustification(ex.getJustification());
                violation.setExpiresAt(ex.getExpiresAt());
                result.getExemptedViolations().add(violation);

                logConsumer.accept(String.format(
                        "  \u001B[35m[EXEMPTED]\u001B[0m Rule '%s' bypassed on '%s' (Ticket: %s, Expires: %s)",
                        violation.getRuleId(), violation.getAddress(), ex.getTicketReference(),
                        ex.getExpiresAt() != null ? ex.getExpiresAt() : "Indefinite"
                ));
            }
        }
    }

    void finalizeResultStatus(
            OpaEvaluationResult result,
            String enforcementLevel,
            String shadowEnforcementLevel,
            Consumer<String> logConsumer) {

        if (STATUS_FAILED.equals(result.getStatus())) {
            int hardCount = Math.max(1, result.getViolations().size());
            result.setHardMandatoryViolations(hardCount);
            result.setExitCode(1);
            return;
        }

        int hardCount = 0;
        int softCount = 0;
        int warningCount = 0;

        // Partition remaining violations according to active enforcement level
        if (ADVISORY.equalsIgnoreCase(enforcementLevel)) {
            for (PolicyViolation v : result.getViolations()) {
                v.setStatus(ViolationStatus.WARNING);
            }
            warningCount = result.getViolations().size();
        } else if (HARD_MANDATORY.equalsIgnoreCase(enforcementLevel)) {
            hardCount = (int) result.getViolations().stream()
                    .filter(v -> v.getStatus() != ViolationStatus.WARNING)
                    .count();
            warningCount = (int) result.getViolations().stream()
                    .filter(v -> v.getStatus() == ViolationStatus.WARNING)
                    .count();
        } else if (SOFT_MANDATORY.equalsIgnoreCase(enforcementLevel)) {
            softCount = (int) result.getViolations().stream()
                    .filter(v -> v.getStatus() != ViolationStatus.WARNING)
                    .count();
            warningCount = (int) result.getViolations().stream()
                    .filter(v -> v.getStatus() == ViolationStatus.WARNING)
                    .count();
        } else {
            warningCount = result.getWarningRules();
        }

        result.setHardMandatoryViolations(hardCount);
        result.setSoftMandatoryViolations(softCount);
        result.setWarningRules(warningCount);

        // Shadow mode telemetry calculation (Gap 11.8)
        if (shadowEnforcementLevel != null && !shadowEnforcementLevel.isBlank()) {
            int nonWarningCount = (int) result.getViolations().stream()
                    .filter(v -> v.getStatus() != ViolationStatus.WARNING)
                    .count();
            if (HARD_MANDATORY.equalsIgnoreCase(shadowEnforcementLevel)) {
                result.setShadowHardViolations(nonWarningCount);
            } else if (SOFT_MANDATORY.equalsIgnoreCase(shadowEnforcementLevel)) {
                result.setShadowSoftViolations(nonWarningCount);
            }
        }

        // Set status and exitCode based on active enforcement level
        if (hardCount > 0) {
            result.setStatus(STATUS_FAILED);
            result.setExitCode(1);
        } else if (softCount > 0) {
            result.setStatus(STATUS_WAITING_APPROVAL);
            result.setExitCode(0); // Soft mandatory waits for approval, doesn't hard-crash plan
        } else if (result.getWarningRules() > 0) {
            result.setStatus(STATUS_WARNING);
            result.setExitCode(0);
        } else {
            result.setStatus(STATUS_PASSED);
            result.setExitCode(0);
        }
    }

    private void saveStructuredContext(TerraformJob job, List<OpaEvaluationResult> results) {
        try {
            int totalPassed = 0;
            int totalWarnings = 0;
            int totalSoft = 0;
            int totalHard = 0;
            int totalShadowHard = 0;
            int totalShadowSoft = 0;
            if (results != null) {
                for (OpaEvaluationResult r : results) {
                    totalPassed += r.getPassedRules();
                    totalWarnings += r.getWarningRules();
                    totalSoft += r.getSoftMandatoryViolations();
                    totalHard += r.getHardMandatoryViolations();
                    totalShadowHard += r.getShadowHardViolations();
                    totalShadowSoft += r.getShadowSoftViolations();
                }
            }

            String overallStatus;
            if (totalHard > 0) {
                overallStatus = STATUS_FAILED;
            } else if (totalSoft > 0) {
                overallStatus = STATUS_WAITING_APPROVAL;
            } else if (totalWarnings > 0) {
                overallStatus = STATUS_WARNING;
            } else {
                overallStatus = STATUS_PASSED;
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("jobId", job.getJobId());
            payload.put("stepId", job.getStepId());
            payload.put("totalPolicies", results != null ? results.size() : 0);
            payload.put("status", overallStatus);
            payload.put("passedRules", totalPassed);
            payload.put("warningRules", totalWarnings);
            payload.put("softMandatoryViolations", totalSoft);
            payload.put("hardMandatoryViolations", totalHard);
            payload.put("shadowHardViolations", totalShadowHard);
            payload.put("shadowSoftViolations", totalShadowSoft);
            payload.put("results", results);

            jobContextService.saveContext(
                    job.getOrganizationId(),
                    job.getJobId(),
                    Map.of("policyEvaluation", payload)
            );
            log.info("Successfully persisted structured policyEvaluation context for job {}", job.getJobId());
        } catch (Exception e) {
            log.warn("Failed to save policyEvaluation context for job {}: {}", job.getJobId(), e.getMessage());
        }
    }
}
