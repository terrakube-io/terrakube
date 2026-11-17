package io.terrakube.executor.service.terraform;

import io.terrakube.executor.service.executor.ExecutorJobResult;
import io.terrakube.executor.service.mode.TerraformJob;

import java.io.File;
import java.io.IOException;

public interface TerraformExecutor {

    ExecutorJobResult plan(TerraformJob terraformJob, File workingDirectory, boolean isDestroy);

    ExecutorJobResult apply(TerraformJob terraformJob, File workingDirectory);

    ExecutorJobResult destroy(TerraformJob terraformJob, File workingDirectory);

    String version();

    /**
     * Download the job's Terraform or OpenTofu version if this executor doesn't have it yet, the same
     * way init gets it for plan, apply and destroy. Does nothing when the job has no version.
     */
    void installBinary(TerraformJob terraformJob) throws IOException;

}
