package io.terrakube.executor.service.infracost;

import io.terrakube.executor.service.mode.TerraformJob;

import java.io.File;
import java.util.function.Consumer;

public interface InfracostExecutorService {

    /**
     * Evaluates pre-deployment infrastructure costs for the given plan using Infracost CLI.
     * Implementations must fail-open so pricing API or binary errors never fail the Terraform plan.
     */
    default InfracostResult evaluateCost(TerraformJob job, File workingDirectory, File planJsonFile) {
        return evaluateCost(job, workingDirectory, planJsonFile, null);
    }

    /**
     * Evaluates pre-deployment infrastructure costs and optionally emits fail-open warnings to {@code planOutput}.
     */
    InfracostResult evaluateCost(
            TerraformJob job,
            File workingDirectory,
            File planJsonFile,
            Consumer<String> planOutput);
}
