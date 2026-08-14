package io.terrakube.registry.service.git;

/**
 * Everything needed to fetch one module version's source from its VCS: where the repository
 * lives, which version was requested, the resolved git tag (nullable, for pre-migration rows),
 * how to authenticate, and the tag/folder conventions configured on the module.
 */
public record ModuleVersionDownload(String repository, String version, String gitTag, String vcsType,
        String vcsConnectionType, String accessToken, String tagPrefix, String folder) {
}
