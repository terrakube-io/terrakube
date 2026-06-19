package io.terrakube.api.rs.provider;

/**
 * Origin of the versions imported for a {@link Provider}.
 */
public enum SourceType {
    /**
     * A Terraform provider registry that implements the provider registry protocol.
     * The host is resolved through service discovery (/.well-known/terraform.json).
     */
    TERRAFORM_REGISTRY,

    /**
     * A plain repository release page or web server hosting goreleaser style assets
     * (terraform-provider-NAME_VERSION_OS_ARCH.zip + SHA256SUMS + SHA256SUMS.sig).
     */
    REPOSITORY
}
