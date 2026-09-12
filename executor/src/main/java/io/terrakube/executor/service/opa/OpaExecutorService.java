package io.terrakube.executor.service.opa;

import io.terrakube.executor.service.executor.ExecutorJobResult;
import io.terrakube.executor.service.mode.PolicyContext;
import io.terrakube.executor.service.mode.PolicyExemptionContext;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.opa.model.OpaEvaluationResult;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

public interface OpaExecutorService {

    /**
     * Evaluates all policy sets attached to the job concurrently against the plan.json file,
     * applies exemptions, and streams buffered logs sequentially to planOutput.
     *
     * @param job              the terraform job containing policyList and policyExemptionList
     * @param workingDirectory workspace working directory
     * @param planJsonFile     the terraform plan.json file to inspect
     * @param planOutput       output consumer for streaming formatted ANSI logs
     * @return list of evaluation results per policy set
     */
    List<OpaEvaluationResult> evaluateAllPolicies(TerraformJob job, File workingDirectory, File planJsonFile, Consumer<String> planOutput);

    /**
     * Evaluates a single policy set against plan.json.
     */
    OpaEvaluationResult evaluatePolicySet(
            PolicyContext policyContext,
            List<PolicyExemptionContext> exemptions,
            File workingDirectory,
            File planJsonFile,
            File policyBundleDir,
            File policyInputsFile,
            Consumer<String> consoleOutput);

    /**
     * Executes headless policy evaluation for scheduled drift detection or compliance scans
     * without running git clone or terraform init.
     */
    ExecutorJobResult evaluateJob(TerraformJob job, File workingDirectory);
}
