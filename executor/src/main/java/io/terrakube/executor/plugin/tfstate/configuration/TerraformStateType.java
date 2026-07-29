package io.terrakube.executor.plugin.tfstate.configuration;

public enum TerraformStateType {
    AzureTerraformStateImpl,
    AwsTerraformStateImpl,

    GcpTerraformStateImpl,
    LocalTerraformStateImpl,
    ApiTerraformStateImpl
}
