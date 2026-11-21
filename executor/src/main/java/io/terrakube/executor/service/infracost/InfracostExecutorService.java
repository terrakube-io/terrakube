package io.terrakube.executor.service.infracost;

import io.terrakube.executor.service.mode.TerraformJob;

import java.io.File;

public interface InfracostExecutorService {

    /**
     * Evaluates pre-deployment infrastructure costs for the given plan using Infracost CLI.
     * Implementations must fail-open so pricing API or binary errors never fail the Terraform plan.
     */
    InfracostResult evaluateCost(TerraformJob job, File workingDirectory, File planJsonFile);
}
