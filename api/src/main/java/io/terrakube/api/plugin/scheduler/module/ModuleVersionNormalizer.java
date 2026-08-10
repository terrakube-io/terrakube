package io.terrakube.api.plugin.scheduler.module;

import com.github.zafarkhaja.semver.ParseException;
import com.github.zafarkhaja.semver.Version;

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

        try {
            Version.parse(remainder);
            return Optional.of(new NormalizedVersion(remainder, gitTag));
        } catch (ParseException e) {
            return Optional.empty();
        }
    }
}
