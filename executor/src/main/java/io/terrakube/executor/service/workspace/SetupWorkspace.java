package io.terrakube.executor.service.workspace;

import io.terrakube.executor.service.mode.TerraformJob;

import java.io.File;

public interface SetupWorkspace {

    File prepareWorkspace(TerraformJob terraformJob) throws WorkspaceException;
}
