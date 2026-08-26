package io.terrakube.api.plugin.scheduler.module;

import org.semver4j.Semver;

import java.util.Optional;

public final class ModuleVersionNormalizer {

    private ModuleVersionNormalizer() {
    }

    public record NormalizedVersion(String canonicalVersion, String originalTag) {
    }

    public static Optional<NormalizedVersion> normalize(String gitTag, String tagPrefix) {
        if (gitTag == null) {
            return Optional.empty();
        }

        String remainder = gitTag;
        if (tagPrefix != null && !tagPrefix.isEmpty()) {
            if (!remainder.startsWith(tagPrefix)) {
                return Optional.empty();
            }
            remainder = remainder.substring(tagPrefix.length());
        }

        if (remainder.startsWith("v")) {
            remainder = remainder.substring(1);
        }

        if (Semver.parse(remainder) == null) {
            return Optional.empty();
        }
        return Optional.of(new NormalizedVersion(remainder, gitTag));
    }
}
